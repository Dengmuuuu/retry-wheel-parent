package com.fastretry.core.metric;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;

public class RetryMeterRegistryProvider {

    private final CompositeMeterRegistry composite;

    public RetryMeterRegistryProvider(List<MeterRegistry> discovered) {
        // 保底 Simple
        this.composite = new CompositeMeterRegistry();
        this.composite.add(new SimpleMeterRegistry());

        // 把外部业务接入的注册表也合入
        if (discovered != null && !discovered.isEmpty()) {
            for (MeterRegistry mr : discovered) {
                if (!(mr instanceof CompositeMeterRegistry)) {
                    this.composite.add(mr);
                } else {
                    ((CompositeMeterRegistry) mr).getRegistries().forEach(this.composite::add);
                }
            }
        }
    }

    public MeterRegistry getRegistry() { return composite; }
}
