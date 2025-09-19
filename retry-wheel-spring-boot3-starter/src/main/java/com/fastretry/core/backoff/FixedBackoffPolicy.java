package com.fastretry.core.backoff;

import com.fastretry.config.RetryWheelProperties;
import com.fastretry.model.entity.RetryTaskEntity;
import com.fastretry.core.spi.BackoffPolicy;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 固定间隔策略（可选小幅抖动）
 */
public class FixedBackoffPolicy implements BackoffPolicy {
    @Override
    public String name() {
        return "fixed";
    }

    @Override
    public Instant next(Instant now, int attempt, Instant deadline, RetryTaskEntity task, RetryWheelProperties props) {
        long base = props.backoffBaseMillis(), min = props.backoffMinMillis(), max = props.backoffMaxMillis();
        double jr = props.getBackoff().getJitterRatio();

        long delay = base;
        if (jr > 0) {
            long jitter = Math.round((ThreadLocalRandom.current().nextDouble(-jr, jr)) * delay);
            delay += jitter;
        }
        delay = Math.max(min, Math.min(delay, max));

        Instant next = now.plusMillis(Math.max(0, delay));
        if (deadline != null && next.isAfter(deadline)) {
            return deadline;
        }
        return next;
    }
}
