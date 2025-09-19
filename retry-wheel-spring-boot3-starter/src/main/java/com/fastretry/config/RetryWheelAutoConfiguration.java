package com.fastretry.config;

import com.fastretry.core.RetryEngine;
import com.fastretry.core.failure.DefaultFailureDecider;
import com.fastretry.core.metric.RetryMetrics;
import com.fastretry.core.serializer.JacksonPayloadSerializer;
import com.fastretry.mapper.RetryTaskMapper;
import com.fastretry.core.spi.BackoffPolicy;
import com.fastretry.core.spi.FailureDecider;
import com.fastretry.core.spi.PayloadSerializer;
import com.fastretry.core.spi.RetryTaskHandler;
import com.fastretry.core.backoff.BackoffRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import io.netty.util.HashedWheelTimer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 时间轮初始化及扫描/补偿组件
 */
@Configuration
@EnableConfigurationProperties(RetryWheelProperties.class)
public class RetryWheelAutoConfiguration {

    /**
     * 时间轮
     */
    @Bean
    public HashedWheelTimer wheelTimer(RetryWheelProperties props) {
        return new HashedWheelTimer(
                new NamedThreadFactory("retry-wheel-timer"),
                props.getWheel().getTickDuration().toMillis(),
                TimeUnit.MILLISECONDS,
                props.getWheel().getTicksPerWheel(),
                false,
                props.getWheel().getMaxPendingTimeouts()
        );
    }

    /**
     * 调度线程池
     */
    @Bean("taskDispatchExecutor")
    public ExecutorService taskDispatchExecutor(RetryWheelProperties props) {
        RetryWheelProperties.Exec exec = props.getExecutor();
        return new ThreadPoolExecutor(
                exec.getCorePoolSize(),
                exec.getMaxPoolSize(),
                exec.getKeepAlive().toSeconds(),
                TimeUnit.SECONDS,
                new LinkedBlockingDeque<>(exec.getQueueCapacity()),
                new NamedThreadFactory("retry-dispatch-exec"),
                exec.getRejectedHandler().toHandler()
        );
    }

    /**
     * handler执行线程池
     */
    @Bean("taskHandlerExecutor")
    public ExecutorService taskHandlerExecutor(RetryWheelProperties props) {
        RetryWheelProperties.Exec exec = props.getExecutor();
        return new ThreadPoolExecutor(
                exec.getCorePoolSize(),
                exec.getMaxPoolSize(),
                exec.getKeepAlive().toSeconds(),
                TimeUnit.SECONDS,
                new LinkedBlockingDeque<>(exec.getQueueCapacity()),
                new NamedThreadFactory("retry-handler-exec"),
                exec.getRejectedHandler().toHandler()
        );
    }

    /**
     * 重试引擎
     */
    @Bean
    public RetryEngine retryEngine(@Autowired HashedWheelTimer timer,
                                   @Qualifier("taskDispatchExecutor") @Autowired ExecutorService dispatchExecutor,
                                   @Qualifier("taskHandlerExecutor") @Autowired ExecutorService handlerExecutor,
                                   @Autowired(required = false) RetryTaskMapper mapper,
                                   @Autowired(required = false) PayloadSerializer serializer,
                                   @Autowired(required = false)  Map<String, RetryTaskHandler<?>> handlers,
                                   @Autowired BackoffRegistry backoffRegistry,
                                   @Autowired(required = false)  FailureDecider failureDecider,
                                   RetryMetrics meter,
                                   RetryWheelProperties props) {
        return new RetryEngine(timer, dispatchExecutor, handlerExecutor, mapper, serializer, handlers,
                backoffRegistry, failureDecider, meter, props);
    }

    /**
     * 策略注册中心
     */
    @Bean
    public BackoffRegistry backoffRegistry(RetryWheelProperties props,
                                           @Autowired(required = false)List<BackoffPolicy> discoveredPolicies) {
        return new BackoffRegistry(props, discoveredPolicies);
    }

    /**
     * 默认序列化
     */
    @Bean
    @ConditionalOnMissingBean(PayloadSerializer.class)
    public PayloadSerializer payloadSerializer() {
        return new JacksonPayloadSerializer();
    }

    /**
     * 默认失败异常判断
     */
    @Bean
    @ConditionalOnMissingBean(FailureDecider.class)
    public FailureDecider failureDecider() {
        return new DefaultFailureDecider();
    }

}
