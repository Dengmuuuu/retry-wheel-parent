package com.fastretry.core.notify.notifier;

import com.fastretry.core.spi.notify.Notifier;
import com.fastretry.model.ctx.NotifyContext;
import com.fastretry.model.enums.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志通知, 默认启用
 */
public class LoggingNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void notify(NotifyContext ctx, Severity severity) {
        switch (severity) {
            case CRITICAL, ERROR -> log.error("[Notify][{}] biz={}, task={}, reason={}, err={}",
                    ctx.getType(), ctx.getBizType(), ctx.getTaskId(), ctx.getReasonCode(), truncate(ctx.getLastError()));
            case WARNING -> log.warn("[Notify][{}] biz={}, task={}, reason={}",
                    ctx.getType(), ctx.getBizType(), ctx.getTaskId(), ctx.getReasonCode());
            default -> log.info("[Notify][{}] biz={}, task={}", ctx.getType(), ctx.getBizType(), ctx.getTaskId());
        }
    }

    private String truncate(String s) {
        return s == null ? null : (s.length() > 2000 ? s.substring(0, 2000) : s);
    }
}
