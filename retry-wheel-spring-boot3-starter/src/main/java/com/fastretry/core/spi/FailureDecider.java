package com.fastretry.core.spi;

import com.fastretry.model.ctx.RetryTaskContext;

/**
 * 失败判定器（可按异常类型/状态码决定是否可重试）
 */
public interface FailureDecider {

    /**
     * @param ctx  当前任务上下文
     * @param t  执行抛出的异常
     * @return     true=建议重试；false=不重试（进入失败或 DLQ 由引擎决定）
     */
    boolean isRetryable(Throwable t, RetryTaskContext ctx);

}
