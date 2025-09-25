package com.fastretry.core.spi.failure;

import com.fastretry.model.ctx.RetryTaskContext;

/**
 * 异常处理器 SPI
 */
public interface FailureCaseHandler<E extends Throwable> {

    /**
     *返回能够处理的异常类型
     */
    Class<E> exceptionType();

    /** 是否匹配 */
    default boolean supports(Throwable t) {
        return exceptionType().isInstance(t);
    }

    /** 产出决策 */
    FailureDecider.Decision execute(E ex, RetryTaskContext ctx);
}
