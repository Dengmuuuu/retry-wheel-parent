package com.fastretry.core.failure;

import com.fastretry.core.spi.failure.FailureDecider;
import com.fastretry.core.spi.failure.FailureDeciderHandler;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 失败决策策略注册中心
 */
public class FailureDeciderHandlerFactory {

    private final Map<FailureDecider.Outcome, FailureDeciderHandler> policies = new ConcurrentHashMap<>(16);

    public FailureDeciderHandlerFactory(List<FailureDeciderHandler> handlers) {
        if (handlers != null) {
            handlers.forEach(p -> registry(p.support(), p));
        }
    }

    /**
     * 获取策略
     */
    public FailureDeciderHandler get(FailureDecider.Decision k) {
        return get(k.getOutcome());
    }

    public FailureDeciderHandler get(FailureDecider.Outcome k) {
        return policies.getOrDefault(k, policies.get(FailureDecider.Outcome.DEAD_LETTER));
    }

    /**
     * 注册
     */
    public FailureDeciderHandlerFactory registry(FailureDecider.Outcome k, FailureDeciderHandler v) {
        policies.put(k, v);
        return this;
    }

        /** 列出已注册策略 */
    public Set<FailureDecider.Outcome> names() { return Collections.unmodifiableSet(policies.keySet()); }
}
