package com.fastretry.core;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.fastretry.config.RetryWheelProperties;
import com.fastretry.core.backoff.BackoffRegistry;
import com.fastretry.core.metric.RetryMetrics;
import com.fastretry.core.spi.FailureDecider;
import com.fastretry.core.spi.PayloadSerializer;
import com.fastretry.core.spi.RetryTaskHandler;
import com.fastretry.mapper.RetryTaskMapper;
import com.fastretry.model.SubmitOptions;
import com.fastretry.model.ctx.RetryTaskContext;
import com.fastretry.model.entity.RetryTaskEntity;
import com.fastretry.model.enums.TaskState;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import io.netty.util.HashedWheelTimer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class RetryEngine implements SmartLifecycle {

    /** 时间轮 */
    private final HashedWheelTimer timer;

    /** 扫描线程池 */
    private final ExecutorService scanExecutor;

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
    private final String nodeId;

    /** 编程式事务 */
    private TransactionTemplate tt;

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
                       TransactionTemplate tt,
                       ApplicationContext applicationContext,
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
        this.tt = tt;
        this.props = props;
        this.scanExecutor = Executors.newFixedThreadPool(props.getStick().isEnable() ? 2 : 1,
                new NamedThreadFactory("retry-scan-exec"));
        this.nodeId = applicationContext.getEnvironment()
                .getProperty("spring.application.name") + "-" + UUID.randomUUID();
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

    /**
     * 扫表
     */
    private void scheduleScanner(long delayMs) {
        Runnable scan = () -> {
            if (!running.get()) {
                log.warn("[ScheduleScanner] retry engine is stop, stop task scan");
                return;
            }
            try {
                scanExecutor.execute(this::lockMarkRunningBatch);
                if (props.getStick().isEnable()) {
                    scanExecutor.execute(this::lockTakeOver);
                }
            } catch (Exception e) {
                log.error("[ScheduleScanner]  scan task error", e);
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
     * 行锁抢占 + 事务来更新状态为RUNNING
     */
    private void lockMarkRunningBatch() {
        List<RetryTaskEntity> tasks = tt.execute(status -> {
            int batch = props.getScan().getBatch();
            // 加行锁获取任务
            List<Long> ids = mapper.lockDueTaskIds(batch);
            // 同一事务下加行锁后更新状态RUNNING
            List<RetryTaskEntity> ret = List.of();
            if (!ids.isEmpty()) {
                if (props.getStick().isEnable()) {
                    // 启用粘滞模式
                    mapper.markRunningAndOwnBatch(ids, nodeId, props.getStick().getLeaseTtl().toSeconds());
                } else {
                    mapper.markRunningBatch(ids, nodeId);
                }
                ret = mapper.selectBatchIds(ids);
            }
            return ret;
        });
        // 提交到调度线程池
        if (tasks != null && !tasks.isEmpty()) {
            tasks.forEach(this::dispatch);
        }
    }

    /**
     * 行锁抢占 + 事务来扫描需要被接管的任务
     */
    private void lockTakeOver() {
        List<RetryTaskEntity> tasks = tt.execute(status -> {
            int batch = props.getScan().getBatch();
            List<Long> ids = mapper.findLeaseExpired(batch);

            List<RetryTaskEntity> ret = List.of();
            if (!ids.isEmpty()) {
                mapper.tryTakeover(ids, nodeId, props.getStick().getLeaseTtl().toSeconds());
                log.info("[Takeover Scan] the current service has taken over the task ids={}", ids);
                ret = mapper.selectBatchIds(ids);
            }
            return ret;
        });
        // 提交到调度线程池
        if (tasks != null && !tasks.isEmpty()) {
            tasks.forEach(this::dispatch);
        }
    }

    /**
     * 线程池调度执行任务
     */
    private void dispatch(RetryTaskEntity task) {
        dispatchExecutor.execute(() -> {
            try {
                // check过期/截止线, 标记进入死信队列
                if (task.getDeadlineTime() != null
                        && LocalDateTime.now().isAfter(task.getDeadlineTime())) {
                    mapper.markDeadLetter(task.getId(), task.getVersion(), "[Dispatch] Expired deadline");
                    log.warn("[Dispatch] task expired status modify to deadLetter, id={}", task.getId());
                    return;
                }

                // 已被接管
                if (StringUtils.isNotEmpty(task.getOwnerNodeId()) && !nodeId.equals(task.getOwnerNodeId())) {
                    log.warn("[Dispatch] task id={} has been taken over by another instance", task.getId());
                    return;
                }

                RetryTaskHandler<?> h = handlers.get(task.getBizType());
                // 对应任务的执行器不存在, 标记进入死信队列
                if (h == null) {
                    mapper.markDeadLetter(task.getId(), task.getVersion(), "No handler");
                    log.warn("[Dispatch] task no handler, status modify to deadLetter, id={}", task.getId());
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

                // 执行前续约
                // now 当前时间 + executionTimeout 最大执行时间 > LeaseExpireAt 租约到期时间 - renewAhead 提前续约窗口
                if (props.getStick().isEnable() && task.getLeaseExpireAt() != null
                        && LocalDateTime.now().plusSeconds(task.getExecuteTimeoutMs().longValue())
                            .isAfter(task.getLeaseExpireAt().minusSeconds(props.getStick().getRenewAhead().toSeconds()))) {
                    // 为防止时钟漂移, 最终以数据库时钟为准
                    mapper.renewLease(task.getId(), nodeId, props.getStick().getLeaseTtl().toSeconds());
                    // 本地推算一下到期时间, 毫秒级误差, 节省一次db查询
                    task.setLeaseExpireAt(LocalDateTime.now().plusSeconds(props.getStick().getLeaseTtl().toSeconds()));
                }

                // 执行 with 超时
                Future<Boolean> f = handlerExecutor.submit(() -> executeWith(h, task, context));
                boolean ret = false;
                try {
                    ret = f.get(task.getExecuteTimeoutMs(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    f.cancel(true);
                    handleFailure(task, new RuntimeException("[Dispatch] Handler execute time out"), context);
                    return;
                } catch (Throwable e) {
                    handleFailure(task, e, context);
                    return;
                }

                if (ret) {
                    mapper.markSuccess(task.getId(), task.getVersion());
                    meter.incSuccess();
                } else {
                    handleFailure(task, new RuntimeException("[Dispatch] Handler returned false"), context);
                }
            } catch (Throwable ex) {
                log.error("[Dispatch] fatal error, {}", ex.getMessage());
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
        meter.incFailed();

        boolean retryable = failureDecider.isRetryable(ex, ctx);
        int nextAttempt = task.getRetryCount() + 1;
        log.info("[HandlerFailure] task execute failed, id={}, attempt={}, err={}, stickMode={}",
                task.getId(), nextAttempt, ex.getMessage(), props.getStick().isEnable());

        // 截断4000字符
        String err = "null";
        if (!StringUtils.isBlank(ex.getMessage())) {
            err = ex.getMessage().substring(Math.min(4000, ex.getMessage().length()));
        }
        // 不可重试或者达到最大重试次数, 将任务标记死信队列
        if (!retryable || nextAttempt > task.getMaxRetry()) {
            log.warn("[HandlerFailure] when the maximum retry count is reached, mark the task as dead letter queue ");
            mapper.markDeadLetter(task.getId(), task.getVersion(), err);
            meter.incDlq();
            return;
        }

        Instant now = Instant.now(), deadline = task.getDeadlineTime() == null ? null : task.getDeadlineTime().toInstant(ZoneOffset.ofHours(8));
        Instant nextTs = backoff.resolve(task.getBackoffStrategy())
                .next(now, nextAttempt, deadline, task, props);
        // 下次执行时间间隔
        long execDelay = nextTs.toEpochMilli() - now.toEpochMilli();
        // 到期时间间隔
        long expiredDelay = Duration.between(LocalDateTime.now(), task.getLeaseExpireAt()).toMillis();

        // 设置下次触发时间, 非粘滞模式
        if (!props.getStick().isEnable()) {
            mapper.markPendingWithNext(
                    task.getId(), task.getVersion(), LocalDateTime.ofInstant(nextTs, ZoneOffset.ofHours(8)), nextAttempt, err);
        } else {
            // 粘滞模式 RUNNING 内循环, 不回PENDING 不换owner
            if (execDelay > expiredDelay) {
                // 下次执行时间超过了当前租约到期时间, 续约到下次执行时间后
                long ttl = Duration.ofMillis(execDelay).plus(props.getStick().getRenewAhead()).toSeconds();
                mapper.renewLease(task.getId(), nodeId, ttl);
                // 本地推算一下到期时间, 毫秒级误差, 节省一次db查询
                task.setLeaseExpireAt(LocalDateTime.ofInstant(
                        nextTs.plusSeconds(props.getStick().getRenewAhead().toSeconds()),
                        ZoneOffset.ofHours(8)));
            }
            // 本地重试写回部分任务信息
            int st = mapper.updateForLocalRetry(task.getId(), nodeId, task.getVersion(),
                    LocalDateTime.ofInstant(nextTs, ZoneOffset.ofHours(8)),
                    nextAttempt, err);
            if (st > 0) {
                // 更新本地任务重试次数及版本
                task.setRetryCount(nextAttempt);
                task.setVersion(task.getVersion() + 1);
            }
            // 将重试任务粘滞到本地时间轮
            timer.newTimeout(t -> dispatch(task), execDelay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 小工具：剥离 CompletionException/ExecutionException 外壳
     */
    private static Throwable unwrap(Throwable ex) {
        if (ex instanceof CompletionException ce && ce.getCause() != null) return ce.getCause();
        if (ex instanceof ExecutionException ee && ee.getCause() != null) return ee.getCause();
        return ex;
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
}
