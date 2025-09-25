package com.fastretry.core.failure.decider;

import com.fastretry.core.spi.failure.FailureCaseHandler;
import com.fastretry.core.spi.failure.FailureDecider;
import com.fastretry.exception.guard.DownstreamOpenCircuitException;
import com.fastretry.model.ctx.RetryTaskContext;

/**
 * 熔断打开
 */
public class OpenCircuitHandler implements FailureCaseHandler<DownstreamOpenCircuitException> {
    @Override
    public Class<DownstreamOpenCircuitException> exceptionType() {
        return DownstreamOpenCircuitException.class;
    }

    @Override
    public FailureDecider.Decision execute(DownstreamOpenCircuitException ex, RetryTaskContext ctx) {
        return FailureDecider.Decision
                .of(FailureDecider.Outcome.RETRY, FailureDecider.Category.OPEN_CIRCUIT)
                .factor(8.0)
                .withCode("CB_OPEN");
    }
}
