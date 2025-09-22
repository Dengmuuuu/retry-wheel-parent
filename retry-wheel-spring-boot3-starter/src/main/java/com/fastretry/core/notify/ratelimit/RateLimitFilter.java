package com.fastretry.core.notify.ratelimit;


import com.fastretry.core.spi.notify.NotifierFilter;
import com.fastretry.model.ctx.NotifyContext;
import com.fastretry.model.enums.Severity;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存窗口限流
 */
public class RateLimitFilter implements NotifierFilter {

    private final long windowMs;

    private final int threshold;

    private volatile long windowStart = System.currentTimeMillis();

    private final ConcurrentHashMap<String, AtomicInteger> counter = new ConcurrentHashMap<>();

    public RateLimitFilter(Duration window, int threshold) {
        this.windowMs = window.toMillis();
        this.threshold = threshold;
    }

    @Override
    public boolean allow(NotifyContext ctx, Severity sev) {
        long now = System.currentTimeMillis();
        // 重制窗口
        if (now - windowStart > windowMs) {
            windowStart = now;
            counter.clear();
        }
        String key = String.format("%s_%s_%s", ctx.getType(), ctx.getBizType(), sev.name());
        int c = counter.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        return c <= threshold;
    }
}
