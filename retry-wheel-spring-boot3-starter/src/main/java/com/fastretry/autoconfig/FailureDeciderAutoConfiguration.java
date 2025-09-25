package com.fastretry.autoconfig;

import com.fastretry.core.failure.FailureDeciderHandlerFactory;
import com.fastretry.core.failure.RouterFailureDecider;
import com.fastretry.core.failure.decider.*;
import com.fastretry.core.failure.handler.DeadLetterDeciderHandler;
import com.fastretry.core.failure.handler.RetryDeciderHandler;
import com.fastretry.core.spi.failure.FailureCaseHandler;
import com.fastretry.core.spi.failure.FailureDecider;
import com.fastretry.core.spi.failure.FailureDeciderHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class FailureDeciderAutoConfiguration {

    // 默认内置一组决策器（用户可通过 Bean 覆盖/新增）
    @Bean
    @ConditionalOnMissingBean(OpenCircuitHandler.class)
    public OpenCircuitHandler openCircuitHandler(){ return new OpenCircuitHandler(); }

    @Bean
    @ConditionalOnMissingBean(RateLimitedHandler.class)
    public RateLimitedHandler rateLimitedHandler(){ return new RateLimitedHandler(); }

    @Bean
    @ConditionalOnMissingBean(BulkheadFullHandler.class)
    public BulkheadFullHandler bulkheadFullHandler(){ return new BulkheadFullHandler(); }

    @Bean
    @ConditionalOnMissingBean(TimeoutHandler.class)
    public TimeoutHandler timeoutHandler(){ return new TimeoutHandler(); }

    @Bean
    @ConditionalOnMissingBean(UnknownHandler.class)
    public UnknownHandler unknownHandler(){ return new UnknownHandler(); }

    // Router 决策器, 把所有 FailureCaseHandler 注入
    @Bean
    @ConditionalOnMissingBean(FailureDecider.class)
    public FailureDecider failureDecider(List<FailureCaseHandler<?>> handlers) {
        return new RouterFailureDecider(handlers);
    }

    // 默认内置一组决策处理器（用户可通过Bean覆盖/新增）
    @Bean
    @ConditionalOnMissingBean(RetryDeciderHandler.class)
    public RetryDeciderHandler retryDeciderHandler() { return new RetryDeciderHandler(); }

    @Bean
    @ConditionalOnMissingBean(DeadLetterDeciderHandler.class)
    public DeadLetterDeciderHandler deadLetterDeciderHandler() { return new DeadLetterDeciderHandler(); }

    // FailureDeciderHandlerFactory 注入所有 FailureDeciderHandler
    @Bean
    @ConditionalOnMissingBean(FailureDeciderHandlerFactory.class)
    public FailureDeciderHandlerFactory failureDeciderHandlerRegistry(List<FailureDeciderHandler> handlers) {
        return new FailureDeciderHandlerFactory(handlers);
    }
}
