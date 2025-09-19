package com.fastretry.model;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;

@Data
@Builder
public class SubmitOptions {

    private String dedupKey;
    private Integer maxRetry;
    private Integer priority;
    private String shardKey;
    private String tenantId;
    private Integer executeTimeoutMs;
    /** 初次延迟 */
    private Duration delay;
    /** fixed | exponential | spi:{name} */
    private String backoffStrategy;
    /** 总体截止时间 */
    private Instant deadline;
}
