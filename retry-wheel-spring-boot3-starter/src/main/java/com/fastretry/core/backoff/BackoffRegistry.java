package com.fastretry.core.backoff;

import com.fastretry.config.RetryWheelProperties;
import com.fastretry.model.entity.RetryTaskEntity;
import com.fastretry.core.spi.BackoffPolicy;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略注册中心：
 * - 内置 fixed / exponential
 * - 解析 "spi:{name}" 映射到外部注册的 BackoffPolicy（name() 返回的名字）
 * - 线程安全
 */
public class BackoffRegistry implements InitializingBean {

    private static final String PREFIX_SPI = "spi:";

    private final Map<String, BackoffPolicy> policies = new ConcurrentHashMap<>(16);

    private final RetryWheelProperties props;

    public BackoffRegistry(RetryWheelProperties props, @Nullable List<BackoffPolicy> discovered) {
        this.props = Objects.requireNonNull(props, "props");
        if (discovered != null) {
            discovered.forEach(p -> registry(p.name(), p));
        }
        // 内置策略
        policies.putIfAbsent("fixed", new FixedBackoffPolicy());
        policies.putIfAbsent("exponential", new ExponentialJitterBackoffPolicy());
    }

    public BackoffRegistry(RetryWheelProperties props) {
        this.props = Objects.requireNonNull(props, "props");
    }

    /**
     * 注册或覆盖策略
     */
    public BackoffRegistry registry(String name, BackoffPolicy policy) {
        String key = normalize(name);
        policies.put(key, policy);
        return this;
    }

    /**
     * 按名称解析策略
     * 支持 spi:{name} 前缀
     */
    public BackoffPolicy resolve(String strategy) {
        // 策略为空, 采用默认exponential策略
        if (strategy == null || strategy.isBlank()) {
            return policies.get("exponential");
        }
        String s = strategy.trim();
        // 获取spi:{name}策略, 不存在则采用默认exponential策略
        if (s.regionMatches(true, 0, PREFIX_SPI, 0, PREFIX_SPI.length())) {
            String spiName = normalize(s.substring((PREFIX_SPI.length())));
            return policies.getOrDefault(spiName, policies.get("exponential"));
        }
        // 获取自定义策略, 不存在则采用默认exponential策略
        String key = normalize(s);
        return policies.getOrDefault(key, policies.get("exponential"));
    }

    /**
     * 计算下一次触发
     */
    public Instant next(String strategy, Instant now, int attempt, @Nullable Instant deadline,
                        RetryTaskEntity task) {
        return resolve(strategy).next(now, attempt, deadline, task, props);
    }

    /** 列出已注册策略 */
    public Set<String> names() { return Collections.unmodifiableSet(policies.keySet()); }

    private static String normalize(String n) { return n.toLowerCase(Locale.ROOT).trim(); }



    @Override
    public void afterPropertiesSet() throws Exception {
        // 参数校验
        long min = props.backoffMinMillis(), max = props.backoffMaxMillis();
        if (max < min) {
            throw new IllegalArgumentException("retry.backoff.max must be >= retry.backoff.min");
        }
    }
}
