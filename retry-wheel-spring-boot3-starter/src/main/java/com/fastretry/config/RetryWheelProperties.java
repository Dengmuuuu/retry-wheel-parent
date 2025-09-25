package com.fastretry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 通用重试框架配置（绑定前缀：retry）
 *
 * YAML 示例：
 * retry:
 *   wheel:
 *     tick-duration: 100ms
 *     ticks-per-wheel: 512
 *     max-pending-timeouts: 100000
 *   scan:
 *     enabled: true
 *     stickMode: false
 *     initial-delay: 200
 *     period: 200
 *     batch: 200
 *   executor:
 *     core-pool-size: 8
 *     max-pool-size: 32
 *     queue-capacity: 1000
 *     keep-alive: 60s
 *     rejected-handler: CALLER_RUNS
 *   backoff:
 *     strategy: exponential
 *     base: 1s
 *     min: 500ms
 *     max: 300s
 *     jitter-ratio: 0.2
 *   default-max-retry: 5
 *   default-execute-timeout: 10s
 *   shutdown:
 *     await: 30s
 *   db:
 *     shard:
 *       enabled: false
 *       count: 16
 *       index: 0
 *   leader:
 *     enabled: false
 *   tx:
 *     propagation: Propagation.REQUIRED
 *     read-only: false
 *     isolation: Isolation.DEFAULT
 *     timeout-seconds: 10
 */
@Validated
@ConfigurationProperties(prefix = "retry")
@Configuration
public class RetryWheelProperties {

    private Stick stick = new Stick();

    private Tx tx = new Tx();

    private Wheel wheel = new Wheel();

    private Scan scan = new Scan();

    private Exec executor = new Exec();

    private Backoff backoff = new Backoff();

    private Shutdown shutdown = new Shutdown();

    private Db db = new Db();

    private Leader leader = new Leader();

    /** 默认最大重试次数 */
    private int defaultMaxRetry = 5;

    /** 默认单次执行超时（建议 Duration 写法：10s） */
    private Duration defaultExecuteTimeout = Duration.ofSeconds(10);

    // ----------------- 嵌套配置对象 -----------------

    public static class Stick {
        /** 是否启用粘滞抢占 */
        private boolean enable = false;

        /** 租约时长 默认30s */
        private Duration leaseTtl = Duration.ofSeconds(30);

        /** 提前续约窗口 默认10s （～ ttl / 3） */
        private Duration renewAhead = Duration.ofSeconds(10);

        /** 安排下一次重试时 是否续约 */
        private boolean renewOnSchedule = true;

        public boolean isEnable() {
            return enable;
        }

        public void setEnable(boolean enable) {
            this.enable = enable;
        }

        public Duration getLeaseTtl() {
            return leaseTtl;
        }

        public void setLeaseTtl(Duration leaseTtl) {
            this.leaseTtl = leaseTtl;
        }

        public Duration getRenewAhead() {
            return renewAhead;
        }

        public void setRenewAhead(Duration renewAhead) {
            this.renewAhead = renewAhead;
        }

        public boolean isRenewOnSchedule() {
            return renewOnSchedule;
        }

        public void setRenewOnSchedule(boolean renewOnSchedule) {
            this.renewOnSchedule = renewOnSchedule;
        }
    }

    public static class Tx {
        /** 默认传播行为 */
        private Propagation propagation = Propagation.REQUIRED;

        /** 只读事务（默认 false） */
        private boolean readOnly = false;

        /** 事务隔离级别（默认 DEFAULT） */
        private Isolation isolation = Isolation.DEFAULT;

        /** 超时（秒，<=0 表示不设置） */
        private int timeoutSeconds = 0;

        public Propagation getPropagation() {
            return propagation;
        }

        public void setPropagation(Propagation propagation) {
            this.propagation = propagation;
        }

        public boolean isReadOnly() {
            return readOnly;
        }

        public void setReadOnly(boolean readOnly) {
            this.readOnly = readOnly;
        }

        public Isolation getIsolation() {
            return isolation;
        }

        public void setIsolation(Isolation isolation) {
            this.isolation = isolation;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Wheel {
        /** 时间轮刻度（Duration 友好写法：100ms、1s） */
        private Duration tickDuration = Duration.ofMillis(100);

        /** 槽位数量（2^n 较佳） */
        private int ticksPerWheel = 512;

        /** 允许挂起的最大 timeout 数量（Netty 参数） */
        private long maxPendingTimeouts = 100_000;

        public Duration getTickDuration() { return tickDuration; }
        public void setTickDuration(Duration tickDuration) { this.tickDuration = tickDuration; }
        public int getTicksPerWheel() { return ticksPerWheel; }
        public void setTicksPerWheel(int ticksPerWheel) { this.ticksPerWheel = ticksPerWheel; }
        public long getMaxPendingTimeouts() { return maxPendingTimeouts; }
        public void setMaxPendingTimeouts(long maxPendingTimeouts) { this.maxPendingTimeouts = maxPendingTimeouts; }
    }

    public static class Scan {
        /** 是否启动扫描 */
        private boolean enabled = false;

        /** 首次扫描延迟 */
        private Duration initialDelay = Duration.ofMillis(200);

        /** 扫描周期 */
        private Duration period = Duration.ofMillis(200);

        /** 每批最大抢占数量 */
        private int batch = 200;

        public Duration getInitialDelay() { return initialDelay; }
        public void setInitialDelay(Duration initialDelay) { this.initialDelay = initialDelay; }
        public Duration getPeriod() { return period; }
        public void setPeriod(Duration period) { this.period = period; }
        public int getBatch() { return batch; }
        public void setBatch(int batch) { this.batch = batch; }
        public boolean isEnabled() { return enabled;}
        public void setEnabled(boolean enabled) { this.enabled = enabled;}
    }

    public static class Exec {
        private int corePoolSize = 8;

        private int maxPoolSize = 32;

        /** 任务队列容量 */
        private int queueCapacity = 1000;

        /** 线程空闲存活时间 */
        private Duration keepAlive = Duration.ofSeconds(60);

        /** 拒绝策略：ABORT | CALLER_RUNS | DISCARD | DISCARD_OLDEST */
        private RejectedHandlerPolicy rejectedHandler = RejectedHandlerPolicy.CALLER_RUNS;

        public int getCorePoolSize() { return corePoolSize; }
        public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public Duration getKeepAlive() { return keepAlive; }
        public void setKeepAlive(Duration keepAlive) { this.keepAlive = keepAlive; }
        public RejectedHandlerPolicy getRejectedHandler() { return rejectedHandler; }
        public void setRejectedHandler(RejectedHandlerPolicy rejectedHandler) { this.rejectedHandler = rejectedHandler; }
    }

    public static class Backoff {
        /** 策略：fixed | exponential | spi:{name} */
        private String strategy = "exponential";

        /** 基础间隔（指数退避的 base）：如 1s */
        private Duration base = Duration.ofSeconds(1);

        /** 最小间隔 */
        private Duration min = Duration.ofMillis(500);

        /** 最大间隔 */
        private Duration max = Duration.ofSeconds(300);

        /** 抖动比例（0~1），例如 0.2 表示 ±20% */
        private double jitterRatio = 0.2;

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public Duration getBase() { return base; }
        public void setBase(Duration base) { this.base = base; }
        public Duration getMin() { return min; }
        public void setMin(Duration min) { this.min = min; }
        public Duration getMax() { return max; }
        public void setMax(Duration max) { this.max = max; }
        public double getJitterRatio() { return jitterRatio; }
        public void setJitterRatio(double jitterRatio) { this.jitterRatio = jitterRatio; }
    }

    public static class Shutdown {
        /** 优雅停机等待时长 */
        private Duration await = Duration.ofSeconds(30);

        public Duration getAwait() { return await; }
        public void setAwait(Duration await) { this.await = await; }
    }

    public static class Db {
        private Shard shard = new Shard();

        public Shard getShard() { return shard; }
        public void setShard(Shard shard) { this.shard = shard; }

        public static class Shard {
            private boolean enabled = false;

            /** 总分片数（CRC32(shard_key) % count） */
            private int count = 16;

            /** 当前实例承担的分片索引（0..count-1） */
            private int index = 0;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public int getCount() { return count; }
            public void setCount(int count) { this.count = count; }
            public int getIndex() { return index; }
            public void setIndex(int index) { this.index = index; }
        }
    }

    public static class Leader {
        /** 是否启用 DB 心跳 Leader 单主扫描 */
        private boolean enabled = false;

        /** 心跳周期（可选，若实现 Leader 时使用） */
        private Duration heartbeat = Duration.ofSeconds(5);

        /** 失效判定阈值（可选） */
        private Duration lease = Duration.ofSeconds(15);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getHeartbeat() { return heartbeat; }
        public void setHeartbeat(Duration heartbeat) { this.heartbeat = heartbeat; }
        public Duration getLease() { return lease; }
        public void setLease(Duration lease) { this.lease = lease; }
    }

    // ----------------- 公共枚举/工具 -----------------

    /** 线程池拒绝策略枚举（YAML 中大小写均可） */
    public enum RejectedHandlerPolicy {
        ABORT, CALLER_RUNS, DISCARD, DISCARD_OLDEST;

        public static RejectedHandlerPolicy from(String v) {
            return RejectedHandlerPolicy.valueOf(v.trim().toUpperCase(Locale.ROOT));
        }
        public RejectedExecutionHandler toHandler() {
            return switch (this) {
                case ABORT -> new ThreadPoolExecutor.AbortPolicy();
                case CALLER_RUNS -> new ThreadPoolExecutor.CallerRunsPolicy();
                case DISCARD -> new ThreadPoolExecutor.DiscardPolicy();
                case DISCARD_OLDEST -> new ThreadPoolExecutor.DiscardOldestPolicy();
            };
        }
    }

    // ----------------- getters/setters 顶层 -----------------

    public Stick getStick() { return stick; }
    public void setStick(Stick stick) { this.stick = stick; }

    public Tx getTx() { return tx; }
    public void setTx(Tx tx) { this.tx = tx; }

    public Wheel getWheel() { return wheel; }
    public void setWheel(Wheel wheel) { this.wheel = wheel; }

    public Scan getScan() { return scan; }
    public void setScan(Scan scan) { this.scan = scan; }

    public Exec getExecutor() { return executor; }
    public void setExecutor(Exec executor) { this.executor = executor; }

    public Backoff getBackoff() { return backoff; }
    public void setBackoff(Backoff backoff) { this.backoff = backoff; }

    public Shutdown getShutdown() { return shutdown; }
    public void setShutdown(Shutdown shutdown) { this.shutdown = shutdown; }

    public Db getDb() { return db; }
    public void setDb(Db db) { this.db = db; }

    public Leader getLeader() { return leader; }
    public void setLeader(Leader leader) { this.leader = leader; }

    public int getDefaultMaxRetry() { return defaultMaxRetry; }
    public void setDefaultMaxRetry(int defaultMaxRetry) { this.defaultMaxRetry = defaultMaxRetry; }

    public Duration getDefaultExecuteTimeout() { return defaultExecuteTimeout; }
    public void setDefaultExecuteTimeout(Duration defaultExecuteTimeout) { this.defaultExecuteTimeout = defaultExecuteTimeout; }

    // ----------------- 便捷换算（可在 Engine 中直接使用） -----------------

    /** 以毫秒返回刻度（供 HashedWheelTimer 使用） */
    public long wheelTickMillis() { return wheel.getTickDuration().toMillis(); }

    /** 扫描周期毫秒 */
    public long scanPeriodMillis() { return scan.getPeriod().toMillis(); }

    /** 首次扫描延迟毫秒 */
    public long scanInitialDelayMillis() { return scan.getInitialDelay().toMillis(); }

    /** 线程池 keepAlive 秒 */
    public long executorKeepAliveSeconds() { return executor.getKeepAlive().toSeconds(); }

    /** 默认执行超时毫秒 */
    public long defaultExecuteTimeoutMillis() { return defaultExecuteTimeout.toMillis(); }

    /** 退避：基础/最小/最大毫秒 */
    public long backoffBaseMillis() { return backoff.getBase().toMillis(); }
    public long backoffMinMillis() { return backoff.getMin().toMillis(); }
    public long backoffMaxMillis() { return backoff.getMax().toMillis(); }
}
