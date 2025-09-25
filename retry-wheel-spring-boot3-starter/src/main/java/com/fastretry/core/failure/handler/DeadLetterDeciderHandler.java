package com.fastretry.core.failure.handler;

import com.fastretry.core.EngineOps;
import com.fastretry.core.notify.NotifyContexts;
import com.fastretry.core.spi.failure.FailureDecider;
import com.fastretry.core.spi.failure.FailureDeciderHandler;
import com.fastretry.model.ctx.RetryTaskContext;
import com.fastretry.model.entity.RetryTaskEntity;
import com.fastretry.model.enums.Severity;

/**
 * 死信队列决策处理
 */
public class DeadLetterDeciderHandler implements FailureDeciderHandler {
    @Override
    public FailureDecider.Outcome support() {
        return FailureDecider.Outcome.DEAD_LETTER;
    }

    @Override
    public boolean handler(RetryTaskEntity task, RetryTaskContext ctx, FailureDecider.Decision d, EngineOps ops) {
        try {
            ops.mapper().markDeadLetter(task.getId(), task.getVersion(), ctx.getErr());
        } catch (Exception e) {
            ops.notifier()
                .fire(NotifyContexts.ctxForPersistFail(ops.nodeId(), task, "markDeadLetter", e), Severity.ERROR);
            throw e;
        }
        ops.meter().incDlq();
        return true;
    }
}
