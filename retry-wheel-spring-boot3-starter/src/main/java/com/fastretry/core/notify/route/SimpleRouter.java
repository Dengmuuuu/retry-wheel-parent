package com.fastretry.core.notify.route;

import com.fastretry.core.spi.notify.Notifier;
import com.fastretry.core.spi.notify.NotifierRouter;
import com.fastretry.model.ctx.NotifyContext;
import com.fastretry.model.enums.Severity;

import java.util.List;

/**
 * 简单路由
 * 所有路由事件统统走log
 */
public class SimpleRouter implements NotifierRouter {

    private final Notifier log;

    public SimpleRouter(Notifier log) {
        this.log = log;
    }

    @Override
    public List<Notifier> route(NotifyContext ctx, Severity severity) {
        return List.of(log);
    }


}
