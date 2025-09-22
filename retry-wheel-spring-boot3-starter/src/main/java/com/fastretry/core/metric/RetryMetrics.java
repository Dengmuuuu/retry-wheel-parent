package com.fastretry.core.metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

public final class RetryMetrics {
    private final Counter enqueued;
    private final Counter success;
    private final Counter failed;
    private final Counter dlq;
    private final Counter scanErr;
    private final Counter notifySuppressed;
    private final Counter notifySent;
    private final Counter nofityFailed;
    private final DistributionSummary attempts;
    private final Timer execTimer;
    private final Timer waitTimer;

    private RetryMetrics(MeterRegistry reg) {
        this.enqueued = Counter.builder("retry.enqueue").description("tasks enqueued").register(reg);
        this.success  = Counter.builder("retry.success").description("tasks succeeded").register(reg);
        this.failed   = Counter.builder("retry.failed").description("tasks failed").register(reg);
        this.dlq      = Counter.builder("retry.dlq").description("tasks dead-lettered").register(reg);
        this.attempts = DistributionSummary.builder("retry.attempts")
                .description("attempt count per task").baseUnit("times").register(reg);
        this.notifySuppressed = Counter.builder("retry.notify.suppressed").description("notify suppressed").register(reg);
        this.notifySent   = Counter.builder("retry.notify.sent").description("notify sent").register(reg);
        this.nofityFailed = Counter.builder("retry.notify.failed").description("notify failed").register(reg);
        this.scanErr = Counter.builder("retry.scan.error").description("retry wheel scan error").register(reg);
        this.execTimer= Timer.builder("retry.exec.time").description("task execution time").register(reg);
        this.waitTimer= Timer.builder("retry.wait.time").description("time from due to start").register(reg);
    }

    public static RetryMetrics create(MeterRegistry reg) { return new RetryMetrics(reg); }

    public void incEnqueued(){ enqueued.increment(); }
    public void incSuccess(){  success.increment(); }
    public void incFailed(){   failed.increment(); }
    public void incDlq(){      dlq.increment(); }
    public void incScanErr(){      scanErr.increment(); }
    public void incNotifySuppressed(){ notifySuppressed.increment();}
    public void incNotifyFailed(){ nofityFailed.increment();}
    public void incNotifySent(){ notifySent.increment();}
    public void recordAttempts(int n){ attempts.record(n); }
    public void recordExecNanos(long nanos){ execTimer.record(nanos, TimeUnit.NANOSECONDS); }
    public void recordWaitMillis(long millis){ waitTimer.record(millis, TimeUnit.MILLISECONDS); }
}
