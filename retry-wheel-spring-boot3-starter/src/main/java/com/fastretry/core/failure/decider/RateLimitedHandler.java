package com.fastretry.core.failure.decider;

import com.fastretry.core.spi.failure.FailureCaseHandler;
import com.fastretry.core.spi.failure.FailureDecider;
import com.fastretry.exception.guard.DownstreamRateLimitedException;
import com.fastretry.model.ctx.RetryTaskContext;

/**
 * 限流拒绝
 */
public class RateLimitedHandler implements FailureCaseHandler<DownstreamRateLimitedException> {
    @Override
    public Class<DownstreamRateLimitedException> exceptionType() {
        return DownstreamRateLimitedException.class;
    }

    @Override
    public FailureDecider.Decision execute(DownstreamRateLimitedException ex, RetryTaskContext ctx) {
        return FailureDecider.Decision.of(FailureDecider.Outcome.RETRY, FailureDecider.Category.RATE_LIMITED)
                .factor(2.0).withCode("RATE_LIMIT");
    }
}
