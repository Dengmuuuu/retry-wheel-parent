package com.fastretry.core.failure.decider;

import com.fastretry.core.spi.failure.FailureCaseHandler;
import com.fastretry.core.spi.failure.FailureDecider;
import com.fastretry.model.ctx.RetryTaskContext;

import java.util.concurrent.TimeoutException;

/**
 * 超时处理
 */
public class TimeoutHandler implements FailureCaseHandler<TimeoutException> {
    @Override
    public Class<TimeoutException> exceptionType() {
        return TimeoutException.class;
    }

    @Override
    public FailureDecider.Decision execute(TimeoutException ex, RetryTaskContext ctx) {
        return FailureDecider.Decision.of(FailureDecider.Outcome.RETRY, FailureDecider.Category.TIMEOUT)
                .factor(1.5).withCode("TIMEOUT");
    }
}
