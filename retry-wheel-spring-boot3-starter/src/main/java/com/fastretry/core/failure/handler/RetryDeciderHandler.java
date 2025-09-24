package com.fastretry.core.failure.handler;

import com.fastretry.core.EngineOps;
import com.fastretry.core.spi.failure.FailureDecider;
import com.fastretry.core.spi.failure.FailureDeciderHandler;
import com.fastretry.mapper.RetryTaskMapper;
import com.fastretry.model.ctx.RetryTaskContext;
import com.fastretry.model.entity.RetryTaskEntity;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 重试决策处理
 */
@Slf4j
public class RetryDeciderHandler implements FailureDeciderHandler {

    @Override
    public FailureDecider.Outcome support() {
        return FailureDecider.Outcome.RETRY;
    }

    @Override
    public boolean handler(RetryTaskEntity task, RetryTaskContext ctx, FailureDecider.Decision d, EngineOps ops) {
        int nextAttempt = task.getRetryCount() + 1;

        Instant nextTs = ops.calcNextTs(task, nextAttempt, d);
        // 下次执行间隔
        long execDelay = Math.max(0, nextTs.toEpochMilli() - Instant.now().toEpochMilli());

        long expiredDelay = Long.MAX_VALUE;
        if (task.getLeaseExpireAt() != null) {
            // 租约到期间隔
            expiredDelay = Duration.between(LocalDateTime.now(), task.getLeaseExpireAt()).toMillis();
        }

        boolean sticky = ops.props().getStick().isEnable();
        // 非粘滞
        if (!sticky) {
            ops.mapper().markPendingWithNext(task.getId(),
                        task.getVersion(),
                        ops.toLocalDateTime(nextTs),
                        nextAttempt,
                        ctx.getErr()
                    );
            return true;
        }
        // 粘滞模式 RUNNING内循环 不回PEDNDING 不换owner
        if (execDelay > expiredDelay) {
            // 续约到下次执行时间 + 租约RenewAhead时间
            long ttl = Duration.ofMillis(execDelay)
                    .plus(ops.props().getStick().getRenewAhead())
                    .toSeconds();
            ops.mapper().renewLease(task.getId(), ops.nodeId(), ttl);
            // 本地租约时间也更新
            task.setLeaseExpireAt(ops.toLocalDateTime(nextTs.plusMillis(ttl)));
        }

        // 本地任务重试次数及版本落db
        int st = ops.mapper().updateForLocalRetry(
                task.getId(), ops.nodeId(), task.getVersion(),
                ops.toLocalDateTime(nextTs), nextAttempt, ctx.getErr()
        );
        if (st > 0) {
            task.setRetryCount(nextAttempt);
            task.setVersion(task.getVersion() + 1);
            task.setNextTriggerTime(ops.toLocalDateTime(nextTs));
        }
        // 入本地时间轮
        ops.scheduleLocalRetry(task, execDelay);
        return true;
    }
}
