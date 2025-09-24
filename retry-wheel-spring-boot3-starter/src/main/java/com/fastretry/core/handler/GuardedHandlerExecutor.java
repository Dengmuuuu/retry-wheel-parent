package com.fastretry.core.handler;

import com.fastretry.config.RetryGuardProperties;
import com.fastretry.core.spi.RetryTaskHandler;
import com.fastretry.exception.guard.DownstreamBulkheadFullException;
import com.fastretry.exception.guard.DownstreamOpenCircuitException;
import com.fastretry.exception.guard.DownstreamRateLimitedException;
import com.fastretry.model.ctx.RetryTaskContext;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class GuardedHandlerExecutor {

    private final RetryGuardProperties props;

    private final ConcurrentHashMap<String, CircuitBreaker> cbCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bulkhead>      bhCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimiter>   rlCache = new ConcurrentHashMap<>();

    public GuardedHandlerExecutor(RetryGuardProperties props) {
        this.props = props;
    }

    /**
     * 统一入口
     * 对 handler.execute(ctx, payload) 增加 CB/BH/RL 装饰后执行
     */
    public <T> boolean execute(String bizType, RetryTaskContext ctx,
                               T payload, RetryTaskHandler<T> handler) throws Exception {
        // 组合装饰 RateLimiter → Bulkhead → CircuitBreaker
        Callable<Boolean> decorated = () -> handler.execute(ctx, payload);

        // RateLimit最外层限流，抑制突发流量
        if (enabled(props.getRateLimiter(), props.getRlPerBiz(), bizType)) {
            RateLimiter rl = rlCache.computeIfAbsent(bizType, k -> buildRl(bizType));
            decorated = RateLimiter.decorateCallable(rl, decorated);
        }

        // Bulkhead 限制下游并发
        if (enabled(props.getBulkhead(), props.getBhPerBiz(), bizType)) {
            Bulkhead bh = bhCache.computeIfAbsent(bizType, k -> buildBh(bizType));
            decorated = Bulkhead.decorateCallable(bh, decorated);
        }

        // CircuitBreaker fail-fast 熔断器
        if (enabled(props.getCircuitBreaker(), props.getCbPerBiz(), bizType)) {
            CircuitBreaker cb = cbCache.computeIfAbsent(bizType, k -> buildCb(bizType));
            decorated = CircuitBreaker.decorateCallable(cb, decorated);
        }

        try {
            return decorated.call();
        } catch (CallNotPermittedException open) {
            // 熔断打开 → 标记 系统性可重试，让 Backoff 拉大
            throw new DownstreamOpenCircuitException(open);
        } catch (BulkheadFullException full) {
            // 并发满 → 也可重试但应延后
            throw new DownstreamBulkheadFullException(full);
        } catch (RequestNotPermitted rnp) {
            // 限流未获许可 → 可重试但延后
            throw new DownstreamRateLimitedException(rnp);
        }
    }

    private RateLimiter buildRl(String bizType) {
        RetryGuardProperties.RlConfig r = props.getRlPerBiz() != null && props.getRlPerBiz().getOrDefault(bizType, null) != null
                ? props.getRlPerBiz().get(bizType) : props.getRateLimiter();
        RateLimiterConfig cfg = RateLimiterConfig.custom()
                .limitForPeriod(r.getLimitForPeriod())
                .limitRefreshPeriod(r.getLimitRefreshPeriod())
                .timeoutDuration(r.getTimeoutDuration())
                .build();
        return RateLimiter.of("rl:" + bizType, cfg);
    }

    private Bulkhead buildBh(String bizType) {
        RetryGuardProperties.BhConfig b =
                props.getBhPerBiz() != null && props.getBhPerBiz().getOrDefault(bizType, null) != null
                        ? props.getBhPerBiz().get(bizType) : props.getBulkhead();

        BulkheadConfig cfg = BulkheadConfig.custom()
                .maxConcurrentCalls(b.getMaxConcurrentCalls())
                .maxWaitDuration(b.getMaxWaitDuration())
                .fairCallHandlingStrategyEnabled(true)
                .build();
        return Bulkhead.of("bh:" + bizType, cfg);
    }

    private CircuitBreaker buildCb(String bizType) {
        RetryGuardProperties.CbConfig c =
                props.getCbPerBiz() != null && props.getCbPerBiz().getOrDefault(bizType, null) != null
                        ? props.getCbPerBiz().get(bizType) : props.getCircuitBreaker();

        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .failureRateThreshold(c.getFailureRateThreshold())
                .slowCallRateThreshold(c.getSlowCallRateThreshold())
                .slowCallDurationThreshold(c.getSlowCallDurationThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(c.getSlidingWindowSize())
                .waitDurationInOpenState(c.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(c.getPermittedNumberOfCallsInHalfOpenState())
                .recordExceptions(Throwable.class) // 在 FailureDecider 再精细化
                .build();
        return CircuitBreaker.of("cb:" + bizType, cfg);
    }

    private static <C> boolean enabled(C defaultCfg, Map<String, C> map, String biz) {
        if (defaultCfg == null) {
            return false;
        }
        // 默认配置
        boolean defEnabled = getEnabled(defaultCfg);
        if (map == null) {
            return defEnabled;
        }
        // 业务配置不为空则使用业务配置
        C bizCfg = map.get(biz);
        return bizCfg == null ? defEnabled : getEnabled(bizCfg);
    }

    private static boolean getEnabled(Object cfg) {
        try {
            Field f = cfg.getClass().getDeclaredField("enabled");
            f.setAccessible(true);
            return (Boolean) f.get(cfg);
        } catch (Exception e) {
            return true;
        }
    }

    public CircuitBreaker getCircuitBreakerIfEnabled(String bizType) {
        if (!enabled(props.getCircuitBreaker(), props.getCbPerBiz(), bizType)) {
            return null;
        }
        return cbCache.computeIfAbsent(bizType, this::buildCb);
    }
}
