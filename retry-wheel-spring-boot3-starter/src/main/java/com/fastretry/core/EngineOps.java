package com.fastretry.core;

import com.fastretry.config.RetryWheelProperties;
import com.fastretry.core.backoff.BackoffRegistry;
import com.fastretry.core.metric.RetryMetrics;
import com.fastretry.core.notify.NotifyingFacade;
import com.fastretry.core.spi.failure.FailureDecider;
import com.fastretry.mapper.RetryTaskMapper;
import com.fastretry.model.entity.RetryTaskEntity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 只暴露策略需要的能力
 * 不暴露实现细节
 */
public interface EngineOps {

    String nodeId();

    RetryWheelProperties props();

    BackoffRegistry backoff();

    RetryTaskMapper mapper();

    NotifyingFacade notifier();

    RetryMetrics meter();

    /** 当前引擎是否在运行 */
    BooleanSupplier running();

    /** 将某个任务提交到 dispatch（策略里只知道提交，不关心实现） */
    Consumer<RetryTaskEntity> dispatcher();

    /** 计算下一次触发时间（带 Decision 的退避倍率） */
    default Instant calcNextTs(RetryTaskEntity task,
                               int nextAttempt,
                               FailureDecider.Decision d) {
        Instant now = Instant.now();
        Instant deadline = task.getDeadlineTime()==null ? null
                : task.getDeadlineTime().toInstant(ZoneOffset.ofHours(8));

        Instant base = backoff().resolve(task.getBackoffStrategy())
                .next(now, nextAttempt, deadline, task, props());

        if (d.getBackoffFactor() > 0 && d.getBackoffFactor() != 1.0) {
            long diff = Math.max(1, base.toEpochMilli() - now.toEpochMilli());
            return now.plusMillis((long)(diff * d.getBackoffFactor()));
        }
        return base;
    }

    /** 将任务在本地时间轮上调度执行（封装 timer + dispatch） */
    void scheduleLocalRetry(RetryTaskEntity task, long delayMs);

    /** Instant -> LocalDateTime(UTC+8) */
    default LocalDateTime toLocalDateTime(Instant ts) {
        return LocalDateTime.ofInstant(ts, ZoneOffset.ofHours(8));
    }
}
