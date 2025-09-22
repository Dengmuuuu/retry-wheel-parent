# retry-notify (通知模块)

> 为重试框架提供 **可插拔、可路由、可限流、可观测** 的通知能力。支持单条通知，覆盖 DLQ、最大重试、不可重试失败、接管、续约失败、引擎异常、持久化异常等事件。

---

## 特性

- **SPI 可插拔**：`Notifier` 接口，内置 `LoggingNotifier`，支持自定义（如 飞书/钉钉/Slack）。
- **异步派发**：`AsyncNotifyingService` 独立线程池 + 指数退避重试，不阻塞主流程。
- **路由/过滤**：按事件/租户/业务/严重级别路由到不同通道；内置限流过滤器。
- **可观测性**：Micrometer 指标 + 结构化日志。
- **开关友好**：通过`NotifyingFacade`封装`AsyncNotifyingService`, `retry.notify.enabled=false`时不装配异步实现, 由门面内部自动降级为NOOP，调用方零判空，无NPE风险。

---

## 快速开始

### 1) 引入（在 Starter 中已自动装配）

确保引入 `retry-wheel-spring-boot3-starter`，通知模块随 Starter 自动装配。

### 2) 配置

```yaml
retry:
  notify:
    enabled: true         
    async:
      core-pool-size: 2
      max-pool-size: 4
      queue-capacity: 2000
      keep-alive: 30s
    rate-limit:
      window: 30s                # 限流窗口
      threshold: 50                  # 限流阈值
```

### 3) 事件模型/严重级别
```JAVA
// 事件模型
public enum NotifyEventType {
  DEAD_LETTER,
  MAX_RETRY_REACHED,
  NON_RETRYABLE_FAILED,
  TAKEOVER,
  LEASE_RENEW_FAILED,
  PERSIST_FAILED,
  ENGINE_ERROR
}

// 事件严重级别
public enum Severity { INFO, WARNING, ERROR, CRITICAL }

```
### 4) 通知上下文
```JAVA
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
}
```

### 5) 通知上下文构造工具类
```JAVA
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

    ...
}
```

### 核心接口/默认实现
#### NotifyingFacade引擎依赖的门面接口
```JAVA
public class NotifyingFacade {
    private final Supplier<AsyncNotifyingService> delegate;

    public NotifyingFacade(ObjectProvider<AsyncNotifyingService> p) {
        // 未启用notify则为 null
        this.delegate = p::getIfAvailable;
    }

    public void fire(NotifyContext ctx, Severity sev) {
        AsyncNotifyingService s = delegate.get();
        if (s != null) s.fire(ctx, sev);
    }
}
```
当`retry.notify.enabled=false`时，AsyncNotifyingService.fire不会执行，引擎无需判空。

### Notifier（SPI）
public interface Notifier {
  String name();                                          
  default boolean supports(NotifyContext ctx) { return true; }
  void notify(NotifyContext ctx, Severity severity) throws Exception;
}
内置实现：`LoggingNotifier`（默认）：结构化日志

### 路由与过滤
```JAVA
public interface NotifierRouter { List<Notifier> route(NotifyContext ctx, Severity s); }
 // 限流/去抖/白名单
public interface NotifierFilter { boolean allow(NotifyContext ctx, Severity s); }
```

### 异步派发
```JAVA
public class AsyncNotifyingService {
    public void fire(NotifyContext ctx, Severity sev) {
        if (filter != null && !filter.allow(ctx, sev)) {
            metrics.incNotifySuppressed();
            return;
        }
        exec.execute(() -> {
            List<Notifier> notifiers = router.route(ctx, sev);
            for (Notifier n : notifiers) {
                try {
                    // 重试
                    int attempt = 0;
                    long backoff = 200;
                    while (true) {
                        try {
                            n.notify(ctx, sev);
                            break;
                        } catch (Exception e) {
                            if (++ attempt >= 3) {
                                throw e;
                            }
                            Thread.sleep(backoff);
                            // 指数退避
                            backoff = Math.min(backoff * 2, 4000);
                        }
                    }
                    metrics.incNotifySent();
                } catch (Exception e) {
                    metrics.incNotifyFailed();
                    log.error("[Notify] channel={} event={} failed", n.name(), ctx.getType(), e);
                }
            }
        });
    }
}
```
### 自定义扩展SPI示例
```JAVA
@Component
public class FeishuNotifier implements Notifier {
  public String name() { return "feishu"; }
  public boolean supports(NotifyContext ctx) {
    return ctx.type() == NotifyEventType.DEAD_LETTER || ctx.type() == NotifyEventType.TAKEOVER;
  }
  public void notify(NotifyContext ctx, Severity s) throws Exception {
    // 调用飞书机器人 webhook
  }
}

// 路由覆盖
@Primary
@Component
class MyRouter implements NotifierRouter {
  private final List<Notifier> all; // 注入所有 Notifier
  public List<Notifier> route(NotifyContext c, Severity s) {
    // 根据租户/业务线/事件类型动态路由（示例略）
    return List.of(find("log"), find("feishu"));
  }
}
```

## 总结FAQ
1) Q: 关闭通知会 NPE 吗？
A: 不会。通过 NotifyingFacade 兜底，所有调用都会被安全吞掉。
...待补充
