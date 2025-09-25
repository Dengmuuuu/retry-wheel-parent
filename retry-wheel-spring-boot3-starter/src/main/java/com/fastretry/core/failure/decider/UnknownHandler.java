package com.fastretry.core.failure.decider;

import com.fastretry.core.spi.failure.FailureCaseHandler;
import com.fastretry.core.spi.failure.FailureDecider;
import com.fastretry.model.ctx.RetryTaskContext;

/**
 * 未知异常, 兜底DLQ
 */
public class UnknownHandler implements FailureCaseHandler<Throwable> {
    @Override
    public Class<Throwable> exceptionType() {
        return Throwable.class;
    }

    @Override
    public FailureDecider.Decision execute(Throwable ex, RetryTaskContext ctx) {
        return FailureDecider.Decision.of(FailureDecider.Outcome.DEAD_LETTER, FailureDecider.Category.UNKNOWN)
                .withCode("UNHANDLED");
    }
}
