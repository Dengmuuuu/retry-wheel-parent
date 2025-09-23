package com.fastretry.core;

import com.fastretry.config.RetryNotifierProperties;
import com.fastretry.config.RetryWheelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.atomic.AtomicBoolean;

public class RetryEngineLifecycle implements SmartLifecycle {

    Logger log = LoggerFactory.getLogger(RetryEngineLifecycle.class);

    private final RetryEngine engine;

    private final RetryWheelProperties props;

    private final RetryNotifierProperties notifyProps;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public RetryEngineLifecycle(RetryEngine engine, RetryWheelProperties props,
                                RetryNotifierProperties notifyProps) {
        this.engine = engine;
        this.props = props;
        this.notifyProps = notifyProps;
    }

    @Override
    public void start() {
        running.compareAndSet(false, props.getScan().isEnabled());
        if (!running.get()) {
            log.info("[Retry-Engine] start skipped, nodeId={}", engine.getNodeId());
            return;
        }
        // —— 打印关键启动信息（一次性）——
        try {
            log.info("┌────────────────────────────────────────────────────────────┐");
            log.info("│ RetryEngine starting...                                                                                                     │");
            log.info("├────────────────────────────────────────────────────────────┤");
            log.info("│ nodeId                      : {}", engine.getNodeId());
            log.info("│ props.scan.period : {} ms", props.getScan().getPeriod().toMillis());
            log.info("│ props.scan.batch  : {}", props.getScan().getBatch());
            log.info("│ props.wheel.tick    : {} ms", props.getWheel().getTickDuration().toMillis());
            log.info("│ props.wheel.size   : {}", props.getWheel().getTicksPerWheel());
            log.info("│ props.exec.core    : {}", props.getExecutor().getCorePoolSize());
            log.info("│ props.exec.max     : {}", props.getExecutor().getMaxPoolSize());
            log.info("│ props.exec.queue  : {}", props.getExecutor().getQueueCapacity());
            log.info("│ props.exec.keepAlive: {} ms", props.getExecutor().getKeepAlive().toMillis());
            log.info("│ backoff.strategy  : {}", props.getBackoff().getStrategy());
            log.info("│ sticky.enabled    : {}", props.getStick().isEnable());
            if (props.getStick().isEnable()) {
                log.info("│ sticky.leaseTtl   : {} ms", props.getStick().getLeaseTtl().toMillis());
                log.info("│ sticky.renewAhead : {} ms", props.getStick().getRenewAhead().toMillis());
            }
            log.info("│ notifier.enabled  : {}", notifyProps.isEnabled());
            log.info("└────────────────────────────────────────────────────────────┘");
        } catch (Throwable t) {
            // 启动日志打印本身不应阻断启动
            log.warn("[Retry-Engine] failed to render startup banner: {}", t.toString());
        }
        // 安排首次扫描
        long initialDelayMs = props.getScan().getInitialDelay().toMillis();
        engine.scheduleScanner();
        log.info("[Retry-Engine] started: first scan in {} ms (nodeId={})", initialDelayMs, engine.getNodeId());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            log.info("[Retry-Engine] stop skipped: already stopped (nodeId={})", engine.getNodeId());
            return;
        }
        log.info("[Retry-Engine] stopping... (nodeId={})", engine.getNodeId());
        // 停止接受新任务, 等待在途完成
        try {
            engine.gracefulShutdown(props.getShutdown().getAwait().toSeconds());
        } finally {
            log.info("[Retry-Engine] stopped (nodeId={})", engine.getNodeId());
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override public boolean isAutoStartup() { return true; }
}
