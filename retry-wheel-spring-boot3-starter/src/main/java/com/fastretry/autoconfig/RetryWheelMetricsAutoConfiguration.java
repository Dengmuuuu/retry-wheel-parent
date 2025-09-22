package com.fastretry.autoconfig;

import com.fastretry.core.metric.RetryMeterRegistryProvider;
import com.fastretry.core.metric.RetryMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
public class RetryWheelMetricsAutoConfiguration {

    @Bean
    public RetryMeterRegistryProvider retryMeterRegistryProvider(
            List<MeterRegistry> discovered) {
        return new RetryMeterRegistryProvider(discovered);
    }

    @Bean
    public RetryMetrics retryMetrics(RetryMeterRegistryProvider provider) {
        return RetryMetrics.create(provider.getRegistry());
    }
}
