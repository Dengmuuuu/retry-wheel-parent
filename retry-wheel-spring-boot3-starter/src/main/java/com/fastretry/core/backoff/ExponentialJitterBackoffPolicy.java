package com.fastretry.core.backoff;

import com.fastretry.config.RetryWheelProperties;
import com.fastretry.model.entity.RetryTaskEntity;
import com.fastretry.core.spi.BackoffPolicy;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public class ExponentialJitterBackoffPolicy implements BackoffPolicy {
    @Override
    public String name() {
        return "exponential";
    }

    @Override
    public Instant next(Instant now, int attempt, Instant deadline, RetryTaskEntity task, RetryWheelProperties props) {
        long base = props.backoffBaseMillis(), min = props.backoffMinMillis(), max = props.backoffMaxMillis();
        double jr = props.getBackoff().getJitterRatio();

        // attempt从1开始计数：1 -> base * 2^1, 2 -> base * 2^2 ...
        double pow = Math.pow(2.0, Math.max(0, attempt));;
        long ideal = (long) Math.min((double)Long.MAX_VALUE, base * pow);

        long jittered = ideal;
        if (jr > 0) {
            long jitter = Math.round(ThreadLocalRandom.current().nextDouble(-jr, jr) * ideal);
            jittered = ideal + jitter;
        }
        long delay = Math.max(min, Math.min(jittered, max));

        Instant next = now.plusMillis(Math.max(0, delay));
        if (deadline != null && next.isAfter(deadline)) {
            return deadline;
        }
        return next;
    }
}
