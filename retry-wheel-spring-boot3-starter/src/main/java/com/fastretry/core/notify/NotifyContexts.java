package com.fastretry.core.notify;

import com.fastretry.model.ctx.NotifyContext;
import com.fastretry.model.entity.RetryTaskEntity;
import com.fastretry.model.enums.NotifyEventType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

public final class NotifyContexts {

    private static final int MAX_ERROR_LEN = 4000;

    private NotifyContexts() {}

    /* ========== 对外入口（使用系统UTC时钟） ========== */

    public static NotifyContext ctxForDlq(String nodeId, RetryTaskEntity t, Throwable e, String reasonCode) {
        return ctxForDlq(nodeId, t, e, reasonCode, Clock.systemUTC().withZone(ZoneOffset.ofHours(8)).withZone(ZoneOffset.ofHours(8)));
    }

    public static NotifyContext ctxForMaxRetry(String nodeId, RetryTaskEntity t, Throwable e) {
        return ctxForMaxRetry(nodeId, t, e, Clock.systemUTC().withZone(ZoneOffset.ofHours(8)));
    }

    public static NotifyContext ctxForNonRetryable(String nodeId, RetryTaskEntity t, Throwable e) {
        return ctxForNonRetryable(nodeId, t, e, Clock.systemUTC().withZone(ZoneOffset.ofHours(8)));
    }

    public static NotifyContext ctxForTakeover(
            String newOwnerNodeId, String taskId, String bizType, String tenantId,
            String oldOwnerNodeId, long oldFence, long newFence) {
        return ctxForTakeover(newOwnerNodeId, taskId, bizType, tenantId, oldOwnerNodeId, oldFence, newFence, Clock.systemUTC().withZone(ZoneOffset.ofHours(8)));
    }

    public static NotifyContext ctxForRenewFail(String nodeId, RetryTaskEntity t, Exception e) {
        return ctxForRenewFail(nodeId, t, e, Clock.systemUTC().withZone(ZoneOffset.ofHours(8)));
    }

    public static NotifyContext ctxForPersistFail(String nodeId, RetryTaskEntity t, String op, Exception e) {
        return ctxForPersistFail(nodeId, t, op, e, Clock.systemUTC().withZone(ZoneOffset.ofHours(8)));
    }

    public static NotifyContext ctxForEngineError(String nodeId, String where, Throwable e) {
        return ctxForEngineError(nodeId, where, e, Clock.systemUTC().withZone(ZoneOffset.ofHours(8)));
    }

    /* ========== 带 Clock 的重载（方便测试 / 注入DB时钟） ========== */

    public static NotifyContext ctxForDlq(String nodeId, RetryTaskEntity t, Throwable e, String reasonCode, Clock clock) {
        Map<String, Object> attrs = baseAttrs(t);
        attrs.put("state", "DEAD_LETTER");
        attrs.put("reasonCode", safe(reasonCode));
        return new NotifyContext(
                NotifyEventType.DEAD_LETTER,
                nodeId,
                t.getBizType(),
                t.getTaskId(),
                t.getTenantId(),
                t.getRetryCount(),
                t.getMaxRetry(),
                safe(reasonCode),
                truncate(toError(e)),
                now(clock),
                attrs
        );
    }

    public static NotifyContext ctxForMaxRetry(String nodeId, RetryTaskEntity t, Throwable e, Clock clock) {
        Map<String, Object> attrs = baseAttrs(t);
        attrs.put("state", "DEAD_LETTER");
        attrs.put("hit", "MAX_RETRY");
        return new NotifyContext(
                NotifyEventType.MAX_RETRY_REACHED,
                nodeId,
                t.getBizType(),
                t.getTaskId(),
                t.getTenantId(),
                t.getRetryCount(),
                t.getMaxRetry(),
                "MAX_RETRY",
                truncate(toError(e)),
                now(clock),
                attrs
        );
    }

    public static NotifyContext ctxForNonRetryable(String nodeId, RetryTaskEntity t, Throwable e, Clock clock) {
        Map<String, Object> attrs = baseAttrs(t);
        attrs.put("state", "FAILED");
        attrs.put("nonRetryable", true);
        return new NotifyContext(
                NotifyEventType.NON_RETRYABLE_FAILED,
                nodeId,
                t.getBizType(),
                t.getTaskId(),
                t.getTenantId(),
                t.getRetryCount(),
                t.getMaxRetry(),
                "NON_RETRYABLE",
                truncate(toError(e)),
                now(clock),
                attrs
        );
    }

    public static NotifyContext ctxForTakeover(
            String newOwnerNodeId, String taskId, String bizType, String tenantId,
            String oldOwnerNodeId, long oldFence, long newFence, Clock clock) {

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("fromOwner", oldOwnerNodeId);
        attrs.put("toOwner", newOwnerNodeId);
        attrs.put("fenceOld", oldFence);
        attrs.put("fenceNew", newFence);

        return new NotifyContext(
                NotifyEventType.TAKEOVER,
                // 当前通知发起者 = 新Owner
                newOwnerNodeId,
                bizType,
                String.valueOf(taskId),
                tenantId,
                null,
                null,
                "TAKEOVER",
                null,
                now(clock),
                attrs
        );
    }

    public static NotifyContext ctxForRenewFail(String nodeId, RetryTaskEntity t, Exception e, Clock clock) {
        Map<String, Object> attrs = baseAttrs(t);
        attrs.put("op", "RENEW_LEASE");
        return new NotifyContext(
                NotifyEventType.LEASE_RENEW_FAILED,
                nodeId,
                t.getBizType(),
                t.getTaskId(),
                t.getTenantId(),
                t.getRetryCount(),
                t.getMaxRetry(),
                "LEASE_RENEW_FAILED",
                truncate(toError(e)),
                now(clock),
                attrs
        );
    }

    public static NotifyContext ctxForPersistFail(String nodeId, RetryTaskEntity t, String op, Exception e, Clock clock) {
        Map<String, Object> attrs = baseAttrs(t);
        attrs.put("op", safe(op)); // markSuccess/markDeadLetter/markPendingWithNext/...
        return new NotifyContext(
                NotifyEventType.PERSIST_FAILED,
                nodeId,
                t.getBizType(),
                t.getTaskId(),
                t.getTenantId(),
                t.getRetryCount(),
                t.getMaxRetry(),
                "PERSIST_FAILED",
                truncate(toError(e)),
                now(clock),
                attrs
        );
    }

    public static NotifyContext ctxForEngineError(String nodeId, String where, Throwable e, Clock clock) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("where", safe(where)); // 触发位置：dispatch-exec、wheel-timeout、scan-loop、executor-reject...
        // 也可加上线程名、池状态等外部信息
        attrs.put("thread", Thread.currentThread().getName());

        return new NotifyContext(
                NotifyEventType.ENGINE_ERROR,
                nodeId,
                /* bizType */ null,        // 引擎级异常无特定任务
                /* taskId  */ null,
                /* tenant  */ null,
                /* retryCount */ null,
                /* maxRetry   */ null,
                /* reasonCode */ "ENGINE_ERROR",
                truncate(toError(e)),
                Instant.now(clock),
                attrs
        );
    }

    /* ========== 私有工具 ========== */

    private static Map<String, Object> baseAttrs(RetryTaskEntity t) {
        Map<String, Object> m = new HashMap<>();
        if (t.getOwnerNodeId() != null) {
            m.put("owner", t.getOwnerNodeId());
        }
        m.put("fence", t.getFenceToken());
        if (t.getNextTriggerTime() != null) {
            m.put("nextTriggerTime", t.getNextTriggerTime().toInstant(ZoneOffset.UTC));
        }
        if (t.getDeadlineTime() != null) {
            m.put("deadlineTime", t.getDeadlineTime().toInstant(ZoneOffset.UTC));
        }
        m.put("id", t.getId());
        m.put("version", t.getVersion());
        return m;
    }

    private static Instant now(Clock clock) {
        return Instant.now(clock);
    }

    private static String toError(Throwable e) {
        if (e == null) return null;
        String msg = e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        // 可附加简短堆栈
        StringBuilder sb = new StringBuilder(msg);
        StackTraceElement[] stack = e.getStackTrace();
        // 只取前10行，避免过长
        int n = Math.min(stack.length, 10);
        for (int i = 0; i < n; i++) sb.append("\n  at ").append(stack[i]);
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > MAX_ERROR_LEN ? s.substring(0, MAX_ERROR_LEN) : s;
    }

    private static String safe(String s) {
        return s == null ? null : s;
    }
}
