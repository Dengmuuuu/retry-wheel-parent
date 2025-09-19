package com.fastretry.core;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.fastretry.config.RetryWheelProperties;
import com.fastretry.core.metric.RetryMetrics;
import com.fastretry.mapper.RetryTaskMapper;
import com.fastretry.model.SubmitOptions;
import com.fastretry.model.ctx.RetryTaskContext;
import com.fastretry.model.entity.RetryTaskEntity;
import com.fastretry.model.enums.TaskState;
import com.fastretry.core.spi.FailureDecider;
import com.fastretry.core.spi.PayloadSerializer;
import com.fastretry.core.spi.RetryTaskHandler;
import com.fastretry.core.backoff.BackoffRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.HashedWheelTimer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class RetryEngine implements SmartLifecycle {

    /** 时间轮 */
    private final HashedWheelTimer timer;

    /** 任务调度线程池 */
    private final ExecutorService dispatchExecutor;

    /** handler执行线程池 */
    private final ExecutorService handlerExecutor;

    /** db mapper */
    private final RetryTaskMapper mapper;

    /** 序列化 */
    private final PayloadSerializer serializer;

    /** 重试任务执行器 */
    private final Map<String, RetryTaskHandler<?>> handlers;

    private final BackoffRegistry backoff;
    /** 失败判定器 */
    private final FailureDecider failureDecider;

    /** 指标 */
    private final RetryMetrics meter;

    /** 重试引擎运行状态 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 节点id */
    private final String nodeId = UUID.randomUUID().toString();

    /** 配置 */
    private final RetryWheelProperties props;

    public RetryEngine(HashedWheelTimer timer,
                       ExecutorService dispatchExecutor,
                       ExecutorService handlerExecutor,
                       RetryTaskMapper mapper,
                       PayloadSerializer serializer,
                       Map<String, RetryTaskHandler<?>> handlers,
                       BackoffRegistry backoffRegistry,
                       FailureDecider failureDecider,
                       RetryMetrics meter,
                       RetryWheelProperties props) {
        this.timer = timer;
        this.dispatchExecutor = dispatchExecutor;
        this.handlerExecutor = handlerExecutor;
        this.mapper = mapper;
        this.serializer = serializer;
        this.handlers = handlers;
        this.backoff = backoffRegistry;
        this.failureDecider = failureDecider;
        this.meter = meter;
        this.props = props;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduleScanner(props.getScan().getInitialDelay().toMillis());
    }

    @Override
    public void stop() {
        running.set(false);
        // 停止接受新任务, 等待在途完成
        // TODO: shutdownAndAwait
        timer.stop();
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    public String submit(String bizType, Object payload, SubmitOptions opt) {
        RetryTaskEntity entity = new RetryTaskEntity();
        entity.setBizType(bizType);
        entity.setTaskId(Optional.ofNullable(opt.getDedupKey()).orElse(UUID.randomUUID().toString()));
        entity.setDedupKey(opt.getDedupKey());
        entity.setState(TaskState.PENDING.code);
        entity.setRetryCount(0);
        entity.setMaxRetry(Optional.ofNullable(opt.getMaxRetry()).orElse(props.getDefaultMaxRetry()));
        entity.setPriority(Optional.ofNullable(opt.getPriority()).orElse(0));
        entity.setShardKey(opt.getShardKey());
        entity.setTenantId(opt.getTenantId());
        entity.setExecuteTimeoutMs(Optional.ofNullable(opt.getExecuteTimeoutMs()).orElse((int) props.getDefaultExecuteTimeout().toMillis()));
        entity.setBackoffStrategy(Optional.ofNullable(opt.getBackoffStrategy()).orElse(props.getBackoff().getStrategy()));
        entity.setPayload(serializer.serialize(payload));
        entity.setHeaders("{}");
        Instant now = Instant.now();
        Instant first = now.plus(Optional.ofNullable(opt.getDelay()).orElse(Duration.ZERO));
        entity.setNextTriggerTime(LocalDateTime.ofInstant(first, ZoneOffset.ofHours(8)));
        entity.setDeadlineTime(opt.getDeadline()==null?null:LocalDateTime.ofInstant(opt.getDeadline(), ZoneOffset.ofHours(8)));
        entity.setCreatedBy("api"); entity.setUpdatedBy("api");
        mapper.insert(entity);
        meter.incEnqueued();
        return entity.getTaskId();
    }

    /**
     * 将"待执行db任务"投递到执行线程池中
     */
    private void scheduleScanner(long delayMs) {
        Runnable scan = () -> {
            if (!running.get()) {
                log.warn("retry engine is stop, stop task scan");
                return;
            }
            try {
                int batch = props.getScan().getBatch();
                // 加行锁获取任务
                List<Long> ids = mapper.lockDueTaskIds(batch);
                if (ids.isEmpty()) {
                    return;
                }
                mapper.markRunningBatch(ids, nodeId);
                ids.forEach(this::dispatch);
            } catch (Exception e) {
                log.error("scan task error", e);
                meter.incScanErr();
            } finally {
                // 将下轮scan挂到时间轮
                scheduleScanner(props.getScan().getPeriod().toMillis());
            }
        };
        // scan任务挂在delayMs后的时间轮上
        timer.newTimeout(t -> scan.run(), delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 线程池调度执行
     */
    private void dispatch(Long id) {
        dispatchExecutor.execute(() -> {
            try {
                RetryTaskEntity task = mapper.selectById(id);
                if (task == null) {
                    log.warn("task not found, id={}", id);
                    return;
                }

                // check过期/截止线, 标记进入死信队列
                if (task.getDeadlineTime() != null
                        && LocalDateTime.now().isAfter(task.getDeadlineTime())) {
                    mapper.markDeadLetter(task.getId(), task.getVersion(), "Expired deadline");
                    log.warn("task expired status modify to deadLetter, id={}", task.getId());
                    return;
                }

                RetryTaskHandler<?> h = handlers.get(task.getBizType());
                // 对应任务的执行器不存在, 标记进入死信队列
                if (h == null) {
                    mapper.markDeadLetter(task.getId(), task.getVersion(), "No handler");
                    log.warn("task no handler, status modify to deadLetter, id={}", task.getId());
                    return;
                }

                RetryTaskContext context = RetryTaskContext.builder()
                        .nodeId(nodeId)
                        .bizType(task.getBizType())
                        .taskId(task.getTaskId())
                        .tenantId(task.getTenantId())
                        .headers(Map.of())
                        .attempt(task.getRetryCount())
                        .maxRetry(task.getMaxRetry())
                        .deadline(task.getDeadlineTime() == null ? null : task.getDeadlineTime().toInstant(ZoneOffset.ofHours(8)))
                        .build();

                // 执行 with 超时
                Future<Boolean> f = handlerExecutor.submit(() -> executeWith(h, task, context));
                boolean ret = false;
                try {
                    ret = f.get(task.getExecuteTimeoutMs(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    f.cancel(true);
                    handleFailure(task, new RuntimeException("Handler execute time out"), context);
                    return;
                } catch (Throwable e) {
                    handleFailure(task, e, context);
                    return;
                }

                if (ret) {
                    mapper.markSuccess(task.getId(), task.getVersion());
                    meter.incSuccess();
                } else {
                    handleFailure(task, new RuntimeException("Handler returned false"), context);
                }
            } catch (Throwable ex) {
                log.error("[dispatch] fatal error, {}", ex.getMessage());
                throw ex;
            }
        });
    }

    /**
     * 具体执行
     */
    private <T> boolean executeWith(RetryTaskHandler<T> handler,
                                    RetryTaskEntity task, RetryTaskContext ctx) throws Exception {
        // 反序列化为T, 类型安全
        T payload = serializer.deserialize(task.getPayload(), handler.payloadType());
        // 调用handler
        return handler.execute(ctx, payload);
    }

    /**
     * 失败处理
     */
    private void handleFailure(RetryTaskEntity task, Throwable ex, RetryTaskContext ctx) {
        log.info("task failed, id={}, err={}", task.getId(), ex.getMessage());
        meter.incFailed();

        boolean retryable = failureDecider.isRetryable(ex, ctx);
        int nextAttempt = task.getRetryCount() + 1;
        // 截断4000字符
        String err = "null";
        if (!StringUtils.isBlank(ex.getMessage())) {
            err = ex.getMessage().substring(Math.min(4000, ex.getMessage().length()));
        }
        // 不可重试或者达到最大重试次数, 将任务标记死信队列
        if (!retryable || nextAttempt > task.getMaxRetry()) {
            mapper.markDeadLetter(task.getId(), task.getVersion(), err);
            meter.incDlq();
            return;
        }

        Instant now = Instant.now(), deadline = task.getDeadlineTime() == null ? null : task.getDeadlineTime().toInstant(ZoneOffset.ofHours(8));
        Instant nextTs = backoff.resolve(task.getBackoffStrategy()).next(now, nextAttempt, deadline, task, props);
        // 设置下次触发时间
        mapper.markPendingWithNext(
                task.getId(), task.getVersion(), LocalDateTime.ofInstant(nextTs, ZoneOffset.ofHours(8)), nextAttempt, err);
    }

    /**
     * 小工具：剥离 CompletionException/ExecutionException 外壳
     */
    private static Throwable unwrap(Throwable ex) {
        if (ex instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null) return ce.getCause();
        if (ex instanceof java.util.concurrent.ExecutionException ee && ee.getCause() != null) return ee.getCause();
        return ex;
    }
}
