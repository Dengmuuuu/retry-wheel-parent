package com.fastretry.core.failure.decider;

import com.fastretry.core.spi.failure.FailureCaseHandler;
import com.fastretry.core.spi.failure.FailureDecider;
import com.fastretry.exception.guard.DownstreamBulkheadFullException;
import com.fastretry.model.ctx.RetryTaskContext;

/**
 * 并发已满
 */
public class BulkheadFullHandler implements FailureCaseHandler<DownstreamBulkheadFullException> {

    @Override
    public Class<DownstreamBulkheadFullException> exceptionType(){ return DownstreamBulkheadFullException.class; }

    @Override
    public FailureDecider.Decision execute(DownstreamBulkheadFullException ex, RetryTaskContext ctx) {
        return FailureDecider.Decision.of(FailureDecider.Outcome.RETRY, FailureDecider.Category.BULKHEAD_FULL)
                .factor(4.0)
                .withCode("BULKHEAD_FULL");
    }
}
