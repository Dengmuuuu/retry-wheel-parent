package com.fastretry.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * retry:
 *   guard:
 *     enabled: true
 *     circuit-breaker:
 *       failure-rate-threshold: 60
 *       slow-call-duration-threshold: 500ms
 *       sliding-window-size: 200
 *       wait-duration-in-open-state: 15s
 *     bulkhead:
 *       enabled: true
 *       max-concurrent-calls: 200
 *       max-wait-duration: 0ms
 *     rate-limiter:
 *       enabled: true
 *       limit-for-period: 300
 *       limit-refresh-period: 100ms
 *       timeout-duration: 10ms
 *     cb-per-biz:
 *       external.pay: { failure-rate-threshold: 30, wait-duration-in-open-state: 5s }
 */
@Data
@ConfigurationProperties(prefix = "retry.guard")
public class RetryGuardProperties {
    /** 开关 */
    private boolean enabled = true;

    /** 默认配置（可被 bizType 覆盖） */
    private CbConfig circuitBreaker = new CbConfig();
    private BhConfig bulkhead = new BhConfig();
    private RlConfig rateLimiter = new RlConfig();

    /** 按 bizType 覆盖 */
    private Map<String, CbConfig> cbPerBiz;
    private Map<String, BhConfig> bhPerBiz;
    private Map<String, RlConfig> rlPerBiz;

    @Data
    public static class CbConfig {
        private boolean enabled = true;
        private float failureRateThreshold = 50f;
        private float slowCallRateThreshold = 100f;
        private Duration slowCallDurationThreshold = Duration.ofMillis(300);
        private int slidingWindowSize = 100;
        private Duration waitDurationInOpenState = Duration.ofSeconds(10);
        private int permittedNumberOfCallsInHalfOpenState = 10;
    }

    @Data
    public static class BhConfig {
        private boolean enabled = false;
        private int maxConcurrentCalls = 100;
        // 0=非阻塞
        private Duration maxWaitDuration = Duration.ofMillis(0);
    }

    @Data
    public static class RlConfig {
        private boolean enabled = false;
        // 每个窗口许可数
        private int limitForPeriod = 200;
        private Duration limitRefreshPeriod = Duration.ofMillis(100);
        // 获取许可最大等待
        private Duration timeoutDuration = Duration.ofMillis(20);
    }
}
