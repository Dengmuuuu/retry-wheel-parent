# ResilientWheel
一款基于 Netty HashedWheelTimer 的高可用分布式重试引擎，当前采用 MySQL 持久化 + 分布式抢占/粘滞租约，未来可无缝迁移至 Redis 等存储。

## 模块结构

```
retry-wheel-parent/
├─ retry-wheel-spring-boot3-starter/ # Starter：核心引擎、自动装配、SPI、Mapper 等
└─ sql/ # 初始化/清理 SQL（建表、索引）
```


---

## 快速开始

### 1) 引入依赖

```xml
<dependency>
  <groupId>com.fastretry</groupId>
  <artifactId>retry-wheel-spring-boot3-starter</artifactId>
  <version>${latest}</version>
</dependency>
```
### 2) 初始化数据库

执行 sql/schema.sql (建表、索引、枚举/注释；包含 retry_task 表及必要索引).

3) 启用与配置（application.yml）
```yml
retry:
  stick:
    enable: true
    lease-ttl: 30s
    renew-ahead: 10s
  tx:
    propagation: REQUIRED
    read-only: false
    isolation: DEFAULT
    timeout-seconds: 10
  wheel:
   tick-duration: 100ms
   ticks-per-wheel: 512
   max-pending-timeouts: 100000
  scan:
   initial-delay: 1000
   period: 2000
   batch: 200
  executor:
   core-pool-size: 8
   max-pool-size: 32
   queue-capacity: 1000
   keep-alive: 60s
   rejected-handler: CALLER_RUNS
  backoff:
   strategy: exponential
   base: 1s
   min: 500ms
   max: 300s
   jitter-ratio: 0.2
  default-max-retry: 5
  default-execute-timeout: 10s

```
### 4) 注册任务处理器示例
```JAVA
@Service(value = "test-biz")
public class CallHandler implements RetryTaskHandler<PayloadModel> {
    @Override
    public boolean supports(String bizType) {
        return "test-biz".equals(bizType);
    }

    @Override
    public boolean execute(RetryTaskContext ctx, PayloadModel payload) throws Exception {
        // 模拟失败
        if (payload.getSimulateStatus() == 500) {
            throw new RuntimeException("remote 500");
        }
        if (payload.getSimulateStatus() == 408) {
            Thread.sleep(5000);
        }
        return true;
    }

    @Override
    public TypeReference<PayloadModel> payloadType() {
        return new TypeReference<PayloadModel>() {};
    }
}
```
### 5) 代码中投递任务
```JAVA
SubmitOptions opt = SubmitOptions.builder()
  .maxRetry(8)
  .priority(10)
  .executeTimeoutMs(4000)
  .backoffStrategy("exponential")
  .delay(Duration.ofSeconds(0))
  .deadline(Instant.now().plus(Duration.ofMinutes(10)))
  .build();
PayloadModel payload = new PayloadModel();
payload.setBody("hello world");
payload.setUrl("www.baidu.com");
payload.setSimulateStatus(408);
String taskId = engine.submit("test-biz", payload, opt);
return Map.of("taskId", taskId);
```

---

## 已实现/待完善/未实现

> ✅ 已实现或具备完整骨架；🟨 已实现核心功能但待完善；⬜️ 待实现.

### Starter 与自动装配
✅ Spring Boot 3.x Starter 形态，自动装配引擎、扫描器、定时轮、默认 SPI(支持覆盖).  
✅ RetryWheelProperties 配置映射（轮盘、扫描、执行器、回退、粘滞租约等）.  

### 调度与执行

✅ Netty HashedWheelTimer 初始化，tick 与槽数可配.  
✅ 扫描器：DB 拉取到期任务 → 抢占（或接管）→ 投递本地执行.  
✅ 执行器：业务线程池可配；采用CompletableFuture 链路 + Timeout机制，异步链路不吞异常.  
✅ 失败回退：固定/指数退避（含 jitter） + BackoffRegistry SPI（自定义）.  
⬜️ 优雅停机：释放本机持有任务 RUNNING→PENDING（next=now()）或允许自然到期接管.  

### 泛型与序列化/失败判定

✅ 提供默认PayloadSerializer（Jackson）实现, 可自定实现覆盖.  
✅ FailureDecider（默认需要重试）：业务基于异常类型与业务返回值判断是否需要重试.  

### 粘滞租约

✅ 表结构新增：owner_node_id、lease_expire_at、fence_token；索引 idx_retry_task_lease.    
✅ 首次抢占：PENDING→RUNNING 同步设置 owner/lease/fence 并挂入本地时间轮.    
✅ 本地重试：保持 RUNNING 与 owner 不变，仅回写 retry_count/next_trigger_time/last_error.   
✅ 接管：lease_expire_at<=now() 条件抢占，其他节点安全接手.   

### 可观测性与运维

🟨 Micrometer 指标：入队、成功、失败、DLQ、重试次数分布、执行时长等；空 MeterRegistry 使用默认/Noop 指标实现保障无侵入.  
⬜️ Actuator 端点：到期积压、线程池使用、各状态计数等.  
⬜️ 管理 REST：查询、暂停/恢复、取消、手动重试、DLQ 拉回（如已暴露基础接口）.  
⬜️ 审计日志：关键状态变更/接管/续约/异常栈保留与截断.  

### 告警通知

✅ 告警通知器 SPI：DLQ、接管失败、续约失败突增等触发器.  

### 多租户/分片

⬜️ 多租户/分片策略 SPI：tenant_id/shard_key 参与扫描与限流；提供路由器扩展点.  






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




# 9.25 ResilientWheel 核心架构改造整合说明

本文档总结了近期对 **ResilientWheel**重试引擎的三大核心改造：

1. **优雅停机机制（Graceful Shutdown）**  
3. **Failure（失败处理）模块重构**

---

## 1️⃣ 优雅停机机制

### 改造背景
原始版本直接调用 `timer.stop()` 停止时间轮，无法保证**未触发的任务**和**在途任务**安全落库，容易造成任务丢失。

### 改造方案
- **新增 `gracefulShutdown(long awaitSeconds)`**：
- **停止接收新任务**：`running.set(false)`  
- **停止扫描线程池**：`scanExecutor.shutdownNow()`  
- **停止时间轮并获取未触发任务**：`Set<Timeout> unprocessed = timer.stop()`  
- **过滤业务任务**：仅处理框架挂入的 `WheelTask.Kind.RETRY` 任务  
- **分批落库**：调用 `mapper.releaseOnShutdownBatch(tasks)` 将未触发的本地任务**回写 DB**（标记回 `PENDING`）
- **关闭执行线程池并等待在途任务完成**：`handlerExecutor`、`dispatchExecutor` 分别 `awaitTermination`，超时强制 `shutdownNow()`
- 日志输出：落库任务数、强制关闭提示

### 改造收益
- **任务不丢失**：所有未触发的重试任务在停机时安全落 DB
- **运维友好**：清晰的 shutdown 日志与统计指标

---

## 3️⃣ Failure 模块重构

### 改造目标
从**单一 boolean 判定**进化为**“异常 → 决策 → 策略执行”**的可扩展架构。

### 核心设计
#### 3.1 Decision 模型
- `Outcome`：`RETRY | DEAD_LETTER | FAILED | PAUSED | CANCELLED`
- `Category`：`OPEN_CIRCUIT | RATE_LIMITED | BULKHEAD_FULL | TIMEOUT | IO | BIZ_4XX | BIZ_RETRYABLE | UNKNOWN`
- `preferSticky`、`backoffFactor`、`code/message`

#### 3.2 异常路由
- **FailureCaseHandler**：一类异常对应一个实现类，产出 `Decision`
- **RouterFailureDecider**：按异常类型匹配最合适的处理器，未匹配 → `DEAD_LETTER`

#### 3.3 决策执行
- **FailureDeciderHandler**：按 `Outcome` 执行 DB 落库/时间轮重试/通知/指标
- **EngineOps**：封装 timer、dispatch、mapper、backoff、notifier、metrics 等能力，策略层完全解耦引擎内部实现。

### RETRY 策略示例
- 计算 `nextTs` (支持 `backoffFactor`)
- 非粘滞：`markPendingWithNext` → DB  
- 粘滞：必要时 `renewLease` → `updateForLocalRetry` → `scheduleLocalRetry` 入时间轮

### 成果
- **强扩展性**：异常分类 + 策略模式，新增异常/策略零侵入
- **运维可观测**：按 Outcome/Category 统计失败类型，告警精准
- **与引擎解耦**：策略仅调用 EngineOps，测试与维护简单

---

## 总体收益

| 改造点 | 成果 |
|-------|------|
| **优雅停机** | 确保未触发任务安全回库，支持多实例滚动发布 |
| **Failure 重构** | 形成可插拔的“异常 → 决策 → 策略”架构，扩展灵活，易测试 |

---

# 9.25 后续优化方向：应对下游调用超时导致的本地时间轮堆积

## 背景

在高并发压测或生产异常场景中，如果某个业务 `handler` 所依赖的**下游服务出现故障**（例如长时间超时），则：

* 引擎会在一次扫表中批量抢占大量到期任务（如 2000 条），并交由本地时间轮调度。
* 所有这些任务在执行时都会因为下游超时进入**粘滞重试**，在本地时间轮上排队。
* 时间轮中同一业务的大量“超时重试”任务，会**占用执行线程池和轮盘槽位**，可能影响其他业务 Handler 的正常执行调度。

这种情况下，如果不进行资源隔离或动态调节，就可能出现**任务堆积**、**扫描周期漂移**、**整体吞吐下降**的问题。

---

## 优化思路

整体方案：**动态批量调节 + 精细化熔断 + 粘滞任务延后**，形成一个“自适应限流 + 快速失败”的综合治理能力。

### 1️⃣ 扫表批量的动态调节

#### 现状
当前扫表批量 (`props.scan.batch`) 为静态配置，无法根据 DB 压力和下游处理情况自动调整。

#### 改造方向
* **实时指标监控**：handlerExecutor 队列长度、时间轮中任务堆积数。
* **动态调整策略**：
  * **队列负载过高** → 自动减小本轮扫描批量（例如从 2000 → 500）。
  * **负载恢复正常** → 逐步增大批量（平滑回升）。
* **实现方式**：
  * 引入自适应算法。
  * 扫描前根据指标动态计算 `batch` 值传入 `lockMarkRunningBatch(batch)`。

这样可以保证在下游异常导致执行变慢时，**DB 扫描频率不变但单次批量减少**，从而降低时间轮和线程池的压力。

---

### 2️⃣ Handler 级快速失败熔断

#### 目标
* **仅针对出问题的业务 Handler** 触发熔断，**其他 Handler 不受影响**。
* 让引擎在短期内**快速拒绝**该 Handler 的新任务，避免继续堆积。

#### 实现
* 在引擎层对每个 `bizType` 引入 **CircuitBreaker**（基于 Resilience4j 或自研简单熔断）。
* 指标采样：
  * 统计该 `bizType` 最近 N 次调用的超时/异常比例。
  * 超过阈值时**打开熔断**，一段时间内直接快速失败。
* 打开熔断后：
  * 新扫描到该 `bizType` 的任务**不直接执行**。
  * 为保证最终一致性，**将任务的下一次触发时间推迟**。
  * 记录熔断告警指标、通知运维。

> 这样可以保证**局部隔离故障**：只让出问题的下游业务减速，其余业务照常执行。

---

### 3️⃣ 超时重试任务的延后挂载

当熔断触发后：
* 这类任务**不立即再次调度**，而是按**更长退避因子**（例如 ×4）计算下一次执行时间。
* 通过 `EngineOps.scheduleLocalRetry(task, delayMs)` 将其**挂在时间轮更靠后的位置**。
* 或者直接 `markPendingWithNext` 回库并延后 `next_trigger_time`，由下次扫描再行处理。

> **目的**：在下游恢复前，减少本地时间轮内的短期重试次数，防止任务爆炸。

---

### 4️⃣ 整体流程示意

            ┌─────────────┐
            │ 扫表器       │
            └─────┬───────┘
                  │
              动态调节批量  
                  ↓
    ┌────────────────────────────────┐
    │ handlerExecutor / 时间轮        │
    │ ┌─────────┐ ┌─────────┐        │
    │ │ BizA    │ │ BizB    │ ...    │
    │ └──┬──────┘ └──┬──────┘        │
    │   超时         正常             │
    │    ↓           ↓               │
    │ 熔断开启       正常执行           │
    │    ↓                           │
    │ 延后挂载/回库                    │
    └────────────────────────────────┘


---

### 5️⃣ 指标与运维

* 新增指标：
  * `scanner.batch.current`：实时扫描批量
  * `circuit.open.count{bizType}`：熔断开启次数
  * `retry.delayed.count{bizType}`：因熔断延后的任务数
* 通知：
  * 熔断开启/关闭时触发 `Notifier` SPI 告警
  * 动态批量调节的上下限变化记录到运维日志

---

### 6️⃣ 预期改造收益

| 维度 | 优化前 | 优化后 |
|------|------|------|
| 下游超时时 | 大量任务瞬间粘滞，时间轮和线程池压力陡增 | 动态限流，按负载自动减小扫描批量 |
| 其他业务影响 | 受拖累，扫描周期漂移 | 熔断仅影响单个 Handler，其它业务正常 |
| 恢复速度 | 依赖手动干预 | 下游恢复后熔断自动关闭，任务自然恢复 |

---

## 总结

通过**动态批量调节 + Handler 级熔断 + 延后挂载**的组合拳：

* 在下游大规模超时的情况下，**平滑降低负载**、**隔离故障**、**保障系统整体可用性**。
* 既保证最终一致性，又避免大规模粘滞任务阻塞其他正常业务的调度。





