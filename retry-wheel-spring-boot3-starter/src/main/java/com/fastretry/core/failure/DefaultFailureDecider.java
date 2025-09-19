package com.fastretry.core.failure;

import com.fastretry.core.spi.FailureDecider;
import com.fastretry.model.ctx.RetryTaskContext;

public class DefaultFailureDecider implements FailureDecider {

    @Override
    public boolean isRetryable(Throwable t, RetryTaskContext ctx) {
        return true;
    }
}
