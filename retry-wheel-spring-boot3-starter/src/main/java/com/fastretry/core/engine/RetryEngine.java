package com.fastretry.core.engine;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.fastretry.config.RetryWheelProperties;
import com.fastretry.core.EngineOps;
import com.fastretry.core.backoff.BackoffRegistry;
import com.fastretry.core.failure.FailureDeciderHandlerFactory;
import com.fastretry.core.handler.GuardedHandlerExecutor;
import com.fastretry.core.metric.RetryMetrics;
import com.fastretry.core.notify.NotifyContexts;
import com.fastretry.core.notify.NotifyingFacade;
import com.fastretry.core.spi.failure.FailureDecider;
import com.fastretry.core.spi.PayloadSerializer;
import com.fastretry.core.spi.RetryTaskHandler;
import com.fastretry.core.spi.failure.FailureDeciderHandler;
import com.fastretry.mapper.RetryTaskMapper;
import com.fastretry.model.SubmitOptions;
import com.fastretry.model.WheelTask;
import com.fastretry.model.ctx.RetryTaskContext;
import com.fastretry.model.entity.RetryTaskEntity;
import com.fastretry.model.enums.Severity;
import com.fastretry.model.enums.TaskState;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 重试框架核心引擎
 */
public class RetryEngine {

    Logger log = LoggerFactory.getLogger(RetryEngine.class);

    /** 时间轮 */
    private final HashedWheelTimer timer;

    /** 扫描线程池 */
    private final ScheduledExecutorService scanExecutor;

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

    /** 通知模块 */
    private final NotifyingFacade notifyService;

    /** 将执行任务的handler进行包装增强 */
    private final GuardedHandlerExecutor guard;

    /** 提供引擎能力给失败策略 不暴露实现细节 */
    private final EngineOps engineOps;

    /** 失败决策处理器工厂 */
    private FailureDeciderHandlerFactory failureHandlerFactory;

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
                       NotifyingFacade notifyService,
                       GuardedHandlerExecutor guard,
                       FailureDeciderHandlerFactory failureHandlerFactory,
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
        this.notifyService = notifyService;
        this.guard = guard;
        this.props = props;
        this.scanExecutor = Executors
                .newSingleThreadScheduledExecutor(new NamedThreadFactory("retry-scan-scheduler"));
        this.nodeId = applicationContext.getEnvironment()
                .getProperty("spring.application.name") + "-" + UUID.randomUUID();
        this.running.compareAndSet(false, props.getScan().isEnabled());
        this.engineOps = new DefaultEngineOps(
                nodeId,
                props,
                backoff,
                mapper,
                notifyService,
                meter,
                timer,
                running::get,
                this::dispatch // 直接把方法引用传进去
        );
        this.failureHandlerFactory = failureHandlerFactory;
    }

    protected String getNodeId() { return nodeId;}

    protected HashedWheelTimer getTimer() { return timer;}

    /**
     * 扫表
     */
    protected void scheduleScanner() {
        Runnable scan = () -> {
            if (!running.get()) {
                scanExecutor.shutdown();
                log.warn("[ScheduleScanner] retry engine is stop, stop task scan");
                return;
            }
            try {
                lockMarkRunningBatch();
                if (props.getStick().isEnable()) {
                    lockTakeOver();
                }
                log.info("[ScheduleScanner] start");
            } catch (Exception e) {
                log.error("[ScheduleScanner]  scan task error", e);
                notifyService.fire(NotifyContexts.ctxForEngineError(nodeId, "engine-core-scan", e), Severity.ERROR);
                meter.incScanErr();
            }
        };
        // 单线程池定时执行
        scanExecutor.scheduleWithFixedDelay(scan,
                props.getScan().getInitialDelay().toMillis(),
                props.getScan().getPeriod().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * 行锁抢占 + 事务来更新状态为RUNNING
     */
    private void lockMarkRunningBatch() {
        List<RetryTaskEntity> tasks = tt.execute(status -> {
            int batch = props.getScan().getBatch();
            // 加行锁获取任务
            List<RetryTaskEntity> lockTasks = mapper.lockDueTaskIds(batch);
            Map<Long, RetryTaskEntity> lockTaskMap = lockTasks.stream()
                    .collect(Collectors.toMap(RetryTaskEntity::getId, Function.identity()));
            List<Long> ids = lockTaskMap.keySet().stream().collect(Collectors.toList());
            // 同一事务下加行锁后更新状态RUNNING
            List<RetryTaskEntity> ret = List.of();
            if (!ids.isEmpty()) {
                if (props.getStick().isEnable()) {
                    // 启用粘滞模式
                    try {
                        mapper.markRunningAndOwnBatch(ids, nodeId, props.getStick().getLeaseTtl().toSeconds());
                    } catch (Exception e) {
                        lockTaskMap.values().forEach(task ->
                                notifyService.fire(NotifyContexts.ctxForPersistFail(nodeId, task, "markRunningAndOwnBatch", e), Severity.ERROR));
                        throw e;
                    }
                } else {
                    try {
                        mapper.markRunningBatch(ids, nodeId);
                    } catch (Exception e) {
                        lockTaskMap.values().forEach(task ->
                                notifyService.fire(NotifyContexts.ctxForPersistFail(nodeId, task, "markRunningBatch", e), Severity.ERROR));
                        throw e;
                    }
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
            List<RetryTaskEntity> oldTasks = mapper.findLeaseExpired(batch);

            List<RetryTaskEntity> newTasks = List.of();
            if (!oldTasks.isEmpty()) {
                Map<Long, RetryTaskEntity> oldTaskMap = oldTasks.stream()
                        .collect(Collectors.toMap(RetryTaskEntity::getId, Function.identity()));
                List<Long> ids = oldTaskMap.keySet().stream().toList();
                try {
                    mapper.tryTakeover(ids, nodeId, props.getStick().getLeaseTtl().toSeconds());
                    log.info("[Takeover Scan] the current service has taken over the task ids={}", ids);
                } catch (Exception e) {
                    oldTaskMap.values().forEach(task ->
                            notifyService.fire(NotifyContexts.ctxForPersistFail(nodeId, task, "tryTakeover", e), Severity.ERROR));
                    throw e;
                }

                newTasks = mapper.selectBatchIds(ids);

                // 接管通知
                newTasks.forEach(task -> {
                    RetryTaskEntity oldTask = oldTaskMap.get(task.getId());
                    notifyService.fire(NotifyContexts.ctxForTakeover(nodeId, task.getTaskId(), task.getBizType(),
                            task.getTenantId(),
                            oldTask.getOwnerNodeId(),
                            oldTask.getFenceToken(),
                            task.getFenceToken()), Severity.ERROR);
                });
            }
            return newTasks;
        });
        // 提交到调度线程池
        if (tasks != null && !tasks.isEmpty()) {
            tasks.forEach(this::dispatch);
        }
    }

    /**
     * 线程池调度执行任务
     */
    protected void dispatch(RetryTaskEntity task) {
        dispatchExecutor.execute(() -> {
            try {
                // check过期/截止线, 标记进入死信队列
                if (task.getDeadlineTime() != null
                        && LocalDateTime.now().isAfter(task.getDeadlineTime())) {
                    try {
                        mapper.markDeadLetter(task.getId(), task.getVersion(), "[Dispatch] Expired deadline");
                        log.warn("[Dispatch] task expired status modify to deadLetter, id={}", task.getId());
                    } catch (Exception e) {
                        notifyService.fire(NotifyContexts.ctxForPersistFail(nodeId, task, "markDeadLetter", e), Severity.ERROR);
                        throw e;
                    }
                    notifyService.fire(NotifyContexts.ctxForDlq(nodeId, task, null, "EXPIRED_DEADLINE"), Severity.ERROR);
                    return;
                }

                // 当前节点时间轮中任务已被其他节点接管
                if (StringUtils.isNotEmpty(task.getOwnerNodeId()) && !nodeId.equals(task.getOwnerNodeId())) {
                    log.warn("[Dispatch] task id={} has been taken over by another instance", task.getId());
                    return;
                }

                RetryTaskHandler<?> h = handlers.get(task.getBizType());
                // 对应任务的执行器不存在, 标记进入死信队列
                if (h == null) {
                    try {
                        mapper.markDeadLetter(task.getId(), task.getVersion(), "No handler");
                        log.warn("[Dispatch] task no handler, status modify to deadLetter, id={}", task.getId());
                    } catch (Exception e) {
                        notifyService.fire(NotifyContexts.ctxForPersistFail(nodeId, task, "markDeadLetter", e), Severity.ERROR);
                        throw e;
                    }
                    notifyService.fire(NotifyContexts.ctxForDlq(nodeId, task, null, "NO_HANDLER"), Severity.ERROR);
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
                    try {
                        // 为防止时钟漂移, 最终以数据库时钟为准
                        mapper.renewLease(task.getId(), nodeId, props.getStick().getLeaseTtl().toSeconds());
                    } catch (Exception e) {
                        notifyService.fire(NotifyContexts.ctxForRenewFail(nodeId, task, e), Severity.WARNING);
                        throw e;
                    }
                    // 本地推算一下到期时间, 毫秒级误差, 节省一次db查询
                    task.setLeaseExpireAt(LocalDateTime.now().plusSeconds(props.getStick().getLeaseTtl().toSeconds()));
                }

                // 执行 with 超时
                long startNanos = System.nanoTime();
                Future<Boolean> f = handlerExecutor.submit(() -> executeWith(h, task, context));
                boolean ret = false;
                try {
                    ret = f.get(task.getExecuteTimeoutMs(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    f.cancel(true);
                    CircuitBreaker cb = guard.getCircuitBreakerIfEnabled(task.getBizType());
                    if (cb != null) {
                        // 外层等待超时, 手动记一次熔断失败
                        cb.onError(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS, te);
                    }
                    context.setErr("Handler execute time out");
                    handleFailure(task, te, context);
                    return;
                } catch (Throwable e) {
                    handleFailure(task, e, context);
                    return;
                }

                if (ret) {
                    try {
                        mapper.markSuccess(task.getId(), task.getVersion());
                    } catch (Exception e) {
                        notifyService.fire(NotifyContexts.ctxForPersistFail(nodeId, task, "markSuccess", e), Severity.ERROR);
                        throw e;
                    }
                    meter.incSuccess();
                } else {
                    handleFailure(task, new RuntimeException("[Dispatch] Handler returned false"), context);
                }
            } catch (Throwable ex) {
                log.error("[Dispatch] error, {}", ex.getMessage());
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
        return guard.execute(ctx.getBizType(), ctx, payload, handler);
    }

    /**
     * 失败处理
     */
    private void handleFailure(RetryTaskEntity task, Throwable ex, RetryTaskContext ctx) {
        meter.incFailed();

        FailureDecider.Decision decision = failureDecider.decide(ex, ctx);
        int nextAttempt = task.getRetryCount() + 1;

        // 截断 4000 字符
        String err = "null";
        if (!StringUtils.isBlank(ex.getMessage())) {
            err = ex.getMessage().substring(Math.min(4000, ex.getMessage().length()));
            ctx.setErr(err);
        }
        // 上限保护 RETRY 但超过 maxRetry → 强制改为 DLQ
        if (decision.getOutcome() == FailureDecider.Outcome.RETRY
                && nextAttempt > task.getMaxRetry()) {
            decision = FailureDecider.Decision.of(FailureDecider.Outcome.DEAD_LETTER, decision.getCategory())
                    .withMsg("MAX_RETRY");
        }

        FailureDeciderHandler h = failureHandlerFactory.get(decision);
        if (h == null) {
            // 没有对应决策处理器 -> 进入DLQ
            h = failureHandlerFactory.get(FailureDecider.Outcome.DEAD_LETTER);
        }
        try {
            h.handler(task, ctx, decision, engineOps);
        } catch (Exception e) {
            notifyService.fire(NotifyContexts.ctxForPersistFail(nodeId, task,
                    "FailureDeciderHandler("+decision.getOutcome()+")", e), Severity.ERROR);
            throw new RuntimeException(e);
        }
    }

    public String submit(String bizType, Object payload, SubmitOptions opt) {
        if (!running.get()) {
            throw new RuntimeException("retry engine is stop, stop task submit");
        }
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
        entity.setCreatedBy(nodeId);
        entity.setUpdatedBy(nodeId);
        mapper.insert(entity);
        meter.incEnqueued();
        return entity.getTaskId();
    }

    protected void gracefulShutdown(long awaitSecond) {
        // 停止接收新任务 and 扫描器
        running.set(false);
        scanExecutor.shutdownNow();
        // 停止时间轮 & 将未触发的业务任务落库
        int drained = drainWheelUnprocessed2Db();
        // 关停执行线程池, 等待在途完成
        handlerExecutor.shutdown();
        dispatchExecutor.shutdown();

        long awaitMs = Math.max(1, awaitSecond) * 1000L;
        try {
            if (!handlerExecutor.awaitTermination(awaitMs, TimeUnit.MILLISECONDS)) {
                handlerExecutor.shutdownNow();
                log.warn("[Retry-Engine] handlerExecutor forced shutdown after {}s", awaitSecond);
            }
            if (!dispatchExecutor.awaitTermination(Math.min(2000, awaitMs), TimeUnit.MILLISECONDS)) {
                dispatchExecutor.shutdownNow();
                log.warn("[Retry-Engine] dispatchExecutor forced shutdown");
            }
        } catch (InterruptedException ie) {
            handlerExecutor.shutdownNow();
            dispatchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[Retry-Engine] graceful shutdown done, drainedWheelTasks={}", drained);
    }

    /**
     * 停止时间轮, 拿到未触发任务, 落db
     */
    private int drainWheelUnprocessed2Db() {
        Set<Timeout> unProcessed = timer.stop();
        if (unProcessed == null || unProcessed.isEmpty()) {
            log.info("[Retry-Engine] timer stopped with no unprocessed timeouts.");
            return 0;
        }
        // 仅处理框架挂入的业务型 Wheel 任务，忽略其他任务
        List<WheelTask> tasksToPersist = new ArrayList<>(unProcessed.size());
        for (Timeout t : unProcessed) {
            if (t == null || t.task() == null) {
                continue;
            }
            if (t.task() instanceof WheelTask wt
                    && wt.getKind() == WheelTask.Kind.RETRY) {
                tasksToPersist.add(wt);
            }
        }
        if (tasksToPersist.isEmpty()) {
            log.info("[Retry-Engine] no business wheel tasks to persist.");
            return 0;
        }
        int ok = 0;
        // 按批量大小分批提交，防止 SQL 过长；没有批量方法时，逐条降级
        final int batchSize = Math.max(100, props.getScan().getBatch());
        for (int i = 0; i < tasksToPersist.size(); i += batchSize) {
            List<WheelTask> slice = tasksToPersist.subList(i, Math.min(i + batchSize, tasksToPersist.size()));
            ok += tryReleaseBatch(slice);
        }
        log.info("[Retry-Engine] persisted {} wheel tasks back to DB as PENDING.", ok);
        return ok;
    }

    /**
     * 批量释放
     */
    private int tryReleaseBatch(List<WheelTask> slice) {
        List<RetryTaskEntity> tasks = slice.stream()
                .map(WheelTask::getTask)
                .toList();
        try {
            return mapper.releaseOnShutdownBatch(tasks);
        } catch (Exception e) {
            log.warn("[Retry-Engine] batch release failed, fallback single. size={}", slice.size(), e);
            tasks.forEach(task ->
                    notifyService.fire(NotifyContexts.ctxForPersistFail(nodeId, task, "releaseOnShutdownBatch", e), Severity.ERROR));
            throw e;
        }
    }
}
