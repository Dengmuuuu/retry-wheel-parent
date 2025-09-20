package com.fastretry.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("retry_task")
@Data
public class RetryTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型 */
    private String bizType;

    /** 业务可见 TaskId */
    private String taskId;

    /** 幂等键 */
    private String dedupKey;

    /**
     * 业务类型
     * 0=PENDING,1=RUNNING,2=SUCCEED,3=FAILED,4=PAUSED,5=CANCELLED,6=DEAD_LETTER
     * */
    private Integer state;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大重试次数 */
    private Integer maxRetry;

    /** 下次触发时间（毫秒级） */
    private LocalDateTime nextTriggerTime;

    /** fixed|exponential|spi:{name} */
    private String backoffStrategy;

    /** 任务载荷 */
    private String payload;

    /** 扩展头/属性 */
    private String headers;

    /** 越大越优先 */
    private Integer priority;

    /** 用于分片路由 mod */
    private String shardKey;

    /** 多租户ID */
    private String tenantId;

    /** 单次执行超时(ms) */
    private Integer executeTimeoutMs;

    /** 任务总体截止时间(超过则直接DLQ) */
    private LocalDateTime deadlineTime;

    /** 最后一次错误信息（可截断） */
    private String lastError;

    /** 乐观锁 */
    private Integer version;

    /** 当前持有者实力Id */
    private String OwnerNodeId;

    /** 租约到期时间 */
    private LocalDateTime leaseExpireAt;

    /** 栅栏版本 */
    private Long fenceToken;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private String createdBy;

    private String updatedBy;
}
