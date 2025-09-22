package com.fastretry.core.spi.notify;

import com.fastretry.model.ctx.NotifyContext;
import com.fastretry.model.enums.Severity;

import java.util.List;

/**
 * 路由：根据事件 → 选择若干 Notifier
 */
public interface NotifierRouter {

    List<Notifier> route(NotifyContext ctx, Severity severity);
}
