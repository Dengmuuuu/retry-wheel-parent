-- 主任务表
CREATE TABLE IF NOT EXISTS retry_task (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK',
    biz_type          VARCHAR(64)     NOT NULL COMMENT '业务类型（用于路由到 Handler）',
    task_id           VARCHAR(64)     NOT NULL COMMENT '业务可见 TaskId（可由框架生成或业务指定）',
    dedup_key         VARCHAR(128)    NULL     COMMENT '去重键，唯一约束防重复入队',
    `state`             TINYINT         NOT NULL COMMENT '0=PENDING,1=RUNNING,2=SUCCEED,3=FAILED,4=PAUSED,5=CANCELLED,6=DEAD_LETTER',
    retry_count       INT             NOT NULL DEFAULT 0 COMMENT '已重试次数',
    max_retry         INT             NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    next_trigger_time DATETIME(3)     NOT NULL COMMENT '下次触发时间（毫秒级）',
    backoff_strategy  VARCHAR(64)     NOT NULL DEFAULT 'fixed' COMMENT 'fixed | exponential | spi:{name}',
    payload           JSON            NULL     COMMENT '任务载荷',
    headers           JSON            NULL     COMMENT '扩展头/属性',
    priority          INT             NOT NULL DEFAULT 0 COMMENT '越大越优先',
    shard_key         VARCHAR(64)     NULL     COMMENT '用于分片路由（mod）',
    tenant_id         VARCHAR(64)     NULL     COMMENT '多租户ID',
    execute_timeout_ms INT            NOT NULL DEFAULT 10000 COMMENT '单次执行超时(ms)',
    deadline_time     DATETIME(3)     NULL     COMMENT '任务总体截止时间(超过则直接DLQ)',
    last_error        TEXT            NULL     COMMENT '最后一次错误信息（可截断）',
    version           INT             NOT NULL DEFAULT 0 COMMENT '乐观锁',
    owner_node_id   varchar(128)  NULL COMMENT '当前持有者实例ID',
    lease_expire_at datetime(3)   NULL COMMENT '租约到期时间',
    fence_token     bigint        NOT NULL DEFAULT 0 COMMENT '栅栏版本，接管/变更时自增',
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by        VARCHAR(64)     NULL,
    updated_by        VARCHAR(64)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dedup (dedup_key),
    KEY idx_biz_task (biz_type, task_id),
    KEY idx_state_time_pri (state, next_trigger_time, priority),
    KEY idx_tenant_state_time (tenant_id, state, next_trigger_time),
    KEY idx_shard_priority_time ((CRC32(shard_key)), priority, next_trigger_time),
    KEY idx_retry_task_lease (lease_expire_at, state) -- 按租约过期找可接管任务
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通用重试任务';

-- 审计/事件表（可选，用于运维追踪）
CREATE TABLE IF NOT EXISTS retry_task_audit (
                                                id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                                task_pk     BIGINT UNSIGNED NOT NULL,
                                                task_id     VARCHAR(64)     NOT NULL,
    biz_type    VARCHAR(64)     NOT NULL,
    from_state  TINYINT         NOT NULL,
    to_state    TINYINT         NOT NULL,
    reason      VARCHAR(64)     NOT NULL COMMENT 'TRANSITION/PAUSE/RESUME/CANCEL/DLQ/RETRY/SUBMIT',
    message     TEXT            NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_taskpk_time (task_pk, created_at),
    KEY idx_biz_task_time (biz_type, task_id, created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务状态变更审计';

-- 简易基于DB心跳的Leader选举（可选）
CREATE TABLE IF NOT EXISTS retry_leader (
                                            id          TINYINT NOT NULL PRIMARY KEY CHECK (id=1),
    node_id     VARCHAR(128) NOT NULL,
    heartbeat_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_node (node_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Leader心跳表';
INSERT IGNORE INTO retry_leader (id, node_id, heartbeat_at) VALUES (1, 'INIT', CURRENT_TIMESTAMP(3));
