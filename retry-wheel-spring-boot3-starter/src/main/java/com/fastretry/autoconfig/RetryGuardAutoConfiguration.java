package com.fastretry.autoconfig;

import com.fastretry.config.RetryGuardProperties;
import com.fastretry.core.handler.GuardedHandlerExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        RetryGuardProperties.class
})
public class RetryGuardAutoConfiguration {

    /**
     * handler统一入口
     */
    @Bean
    @ConditionalOnMissingBean
    public GuardedHandlerExecutor guard(RetryGuardProperties props) {
        return new GuardedHandlerExecutor(props);
    }
}
