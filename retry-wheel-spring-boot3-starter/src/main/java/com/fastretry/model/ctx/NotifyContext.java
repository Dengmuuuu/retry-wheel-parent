package com.fastretry.model.ctx;


import com.fastretry.model.enums.NotifyEventType;

import java.time.Instant;
import java.util.Map;

/**
 * 事件上下文
 */
public class NotifyContext {

    private NotifyEventType type;
    private String nodeId;
    private String bizType;
    private String taskId;
    private String tenantId;
    private Integer retryCount;
    private Integer maxRetry;
    // 自定义分类码，如 TIMEOUT/NO_HANDLER/SERDE_ERROR
    private String reasonCode;
    // 可被截断/脱敏
    private String lastError;
    // 事件发生时间
    private Instant when;
    // 额外字段：shardKey、owner、fence、nextTriggerTime 等
    private Map<String, Object> attributes ;

    public NotifyContext() {
    }

    public NotifyContext(NotifyEventType type, String nodeId, String bizType, String taskId, String tenantId, Integer retryCount, Integer maxRetry, String reasonCode, String lastError, Instant when, Map<String, Object> attributes) {
        this.type = type;
        this.nodeId = nodeId;
        this.bizType = bizType;
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.retryCount = retryCount;
        this.maxRetry = maxRetry;
        this.reasonCode = reasonCode;
        this.lastError = lastError;
        this.when = when;
        this.attributes = attributes;
    }

    public NotifyEventType getType() {
        return type;
    }

    public void setType(NotifyEventType type) {
        this.type = type;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Integer getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(Integer maxRetry) {
        this.maxRetry = maxRetry;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getWhen() {
        return when;
    }

    public void setWhen(Instant when) {
        this.when = when;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }
}
