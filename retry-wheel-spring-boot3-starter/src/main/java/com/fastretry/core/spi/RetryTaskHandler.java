package com.fastretry.core.spi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fastretry.model.ctx.RetryTaskContext;

/**
 * 重试任务执行器
 */
public interface RetryTaskHandler<T> {

    boolean supports(String bizType);

    /** 返回 true=成功；抛异常或返回 false=失败（进入重试/失败策略）*/
    boolean execute(RetryTaskContext ctx, T payload) throws Exception;

    /** 负载类型 */
    TypeReference<T> payloadType();
}
