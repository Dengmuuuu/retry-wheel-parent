package com.fastretry.core.spi.failure;

import com.fastretry.core.EngineOps;
import com.fastretry.model.ctx.RetryTaskContext;
import com.fastretry.model.entity.RetryTaskEntity;

/**
 * 根据决策作出处理
 */
public interface FailureDeciderHandler {

    FailureDecider.Outcome support();

    boolean handler(RetryTaskEntity task, RetryTaskContext ctx, FailureDecider.Decision d, EngineOps ops);
}
