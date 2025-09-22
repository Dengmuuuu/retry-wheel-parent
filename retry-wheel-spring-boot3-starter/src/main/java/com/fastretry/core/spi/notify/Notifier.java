package com.fastretry.core.spi.notify;

import com.fastretry.model.ctx.NotifyContext;
import com.fastretry.model.enums.Severity;

/**
 * 告警通知器（进入DLQ/连续失败阈值等）
 */
public interface Notifier {

    /**
     * 返回此Notifier支持的渠道/名称, 用于路由日志与指标纬度
     */
    String name();

    /**
     * 能否处理此事件, 粗粒度过滤
     */
    default boolean supports(NotifyContext ctx) {
        return true;
    }

    /**
     * 派发通知, 同步方法 框架层负责异步调用
     */
    void notify(NotifyContext ctx, Severity severity);

}
