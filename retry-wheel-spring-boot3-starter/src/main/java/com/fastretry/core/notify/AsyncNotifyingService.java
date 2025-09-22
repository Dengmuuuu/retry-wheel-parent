package com.fastretry.core.notify;

import com.fastretry.core.metric.RetryMetrics;
import com.fastretry.core.spi.notify.Notifier;
import com.fastretry.core.spi.notify.NotifierFilter;
import com.fastretry.core.spi.notify.NotifierRouter;
import com.fastretry.model.ctx.NotifyContext;
import com.fastretry.model.enums.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 异步派发
 * 通过路由、限流、异步执行通知
 */
public class AsyncNotifyingService {

    private final Logger log = LoggerFactory.getLogger(AsyncNotifyingService.class);

    private final ExecutorService exec;

    private final NotifierRouter router;

    private final NotifierFilter filter;

    private final RetryMetrics metrics;

    public AsyncNotifyingService(ExecutorService exec, NotifierRouter router, NotifierFilter filter, RetryMetrics metrics) {
        this.exec = exec;
        this.router = router;
        this.filter = filter;
        this.metrics = metrics;
    }

    public void fire(NotifyContext ctx, Severity sev) {
        if (filter != null && !filter.allow(ctx, sev)) {
            metrics.incNotifySuppressed();
            return;
        }
        exec.execute(() -> {
            List<Notifier> notifiers = router.route(ctx, sev);
            for (Notifier n : notifiers) {
                try {
                    // 重试
                    int attempt = 0;
                    long backoff = 200;
                    while (true) {
                        try {
                            n.notify(ctx, sev);
                            break;
                        } catch (Exception e) {
                            if (++ attempt >= 3) {
                                throw e;
                            }
                            Thread.sleep(backoff);
                            // 指数退避
                            backoff = Math.min(backoff * 2, 4000);
                        }
                    }
                    metrics.incNotifySent();
                } catch (Exception e) {
                    metrics.incNotifyFailed();
                    log.error("[Notify] channel={} event={} failed", n.name(), ctx.getType(), e);
                }
            }
        });
    }
}
