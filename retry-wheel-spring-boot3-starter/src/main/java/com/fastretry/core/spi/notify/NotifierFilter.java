package com.fastretry.core.spi.notify;

import com.fastretry.model.ctx.NotifyContext;
import com.fastretry.model.enums.Severity;

/**
 * 过滤器：限流、去抖、按租户/业务白名单过滤等
 */
public interface NotifierFilter {

    /**
     * 返回 true 表示放行，false 表示丢弃/抑制
     */
    boolean allow(NotifyContext ctx, Severity severity);
}
