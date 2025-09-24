package com.fastretry.core.engine;

import com.fastretry.config.RetryWheelProperties;
import com.fastretry.core.EngineOps;
import com.fastretry.core.backoff.BackoffRegistry;
import com.fastretry.core.metric.RetryMetrics;
import com.fastretry.core.notify.NotifyingFacade;
import com.fastretry.mapper.RetryTaskMapper;
import com.fastretry.model.WheelTask;
import com.fastretry.model.entity.RetryTaskEntity;
import io.netty.util.HashedWheelTimer;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class DefaultEngineOps implements EngineOps {

    private final String nodeId;
    private final RetryWheelProperties props;
    private final BackoffRegistry backoff;
    private final RetryTaskMapper mapper;
    private final NotifyingFacade notifier;
    private final RetryMetrics meter;
    private final HashedWheelTimer timer;
    private final BooleanSupplier running;
    private final Consumer<RetryTaskEntity> dispatcher;

    public DefaultEngineOps(String nodeId,
                            RetryWheelProperties props,
                            BackoffRegistry backoff,
                            RetryTaskMapper mapper,
                            NotifyingFacade notifier,
                            RetryMetrics meter,
                            HashedWheelTimer timer,
                            BooleanSupplier running,
                            Consumer<RetryTaskEntity> dispatcher) {
        this.nodeId = nodeId;
        this.props = props;
        this.backoff = backoff;
        this.mapper = mapper;
        this.notifier = notifier;
        this.meter = meter;
        this.timer = timer;
        this.running = running;
        this.dispatcher = dispatcher;
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public RetryWheelProperties props() {
        return props;
    }

    @Override
    public BackoffRegistry backoff() {
        return backoff;
    }

    @Override
    public RetryTaskMapper mapper() {
        return mapper;
    }

    @Override
    public NotifyingFacade notifier() {
        return notifier;
    }

    @Override
    public RetryMetrics meter() {
        return meter;
    }

    @Override
    public BooleanSupplier running() {
        return running;
    }

    @Override
    public Consumer<RetryTaskEntity> dispatcher() {
        return dispatcher;
    }

    @Override
    public void scheduleLocalRetry(RetryTaskEntity task, long delayMs) {
        if (!running.getAsBoolean()) {
            return;
        }
        timer.newTimeout(
                new WheelTask(WheelTask.Kind.RETRY, task, true, () -> dispatcher.accept(task)),
                delayMs,
                TimeUnit.MILLISECONDS
        );
    }
}
