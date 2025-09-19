-- cleanup.sql
-- 1) 将完成/死信且超过保留期的记录归档或删除（示例保留30天）
-- 推荐：先归档到历史表，再删除原表。以下给出直接删除示例。

-- 删除 30天前的 SUCCEED/DEAD_LETTER/CANCELLED
DELETE FROM retry_task
WHERE state IN (2, 5, 6) -- SUCCEED, CANCELLED, DEAD_LETTER
  AND updated_at < (CURRENT_TIMESTAMP(3) - INTERVAL 30 DAY)
    LIMIT 10000;

-- 审计表保留90天
DELETE FROM retry_task_audit
WHERE created_at < (CURRENT_TIMESTAMP(3) - INTERVAL 90 DAY)
    LIMIT 20000;
