package com.fastretry.model.ctx;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class RetryTaskContext {

    private String nodeId;
    private String bizType;
    private String taskId;
    private String tenantId;
    private String err;
    private Map<String, String> headers;
    private int attempt;
    private int maxRetry;
    private Instant deadline;
}
