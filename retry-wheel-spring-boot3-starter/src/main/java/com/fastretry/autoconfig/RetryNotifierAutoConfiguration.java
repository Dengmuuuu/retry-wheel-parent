package com.fastretry.autoconfig;

import com.fastretry.config.RetryNotifierProperties;
import com.fastretry.core.metric.RetryMetrics;
import com.fastretry.core.notify.AsyncNotifyingService;
import com.fastretry.core.notify.NotifyingFacade;
import com.fastretry.core.notify.notifier.LoggingNotifier;
import com.fastretry.core.notify.ratelimit.RateLimitFilter;
import com.fastretry.core.notify.route.SimpleRouter;
import com.fastretry.core.spi.notify.Notifier;
import com.fastretry.core.spi.notify.NotifierRouter;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(RetryNotifierProperties.class)
public class RetryNotifierAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "loggingNotifier")
    public Notifier loggingNotifier() {
        return new LoggingNotifier();
    }

    @Bean
    @ConditionalOnMissingBean(NotifierRouter.class)
    public NotifierRouter notifierRouter(@Qualifier("loggingNotifier") Notifier logging) {
        return new SimpleRouter(logging);
    }

    @Bean
    @ConditionalOnProperty(prefix = "retry.notify", name = "enabled")
    public AsyncNotifyingService asyncNotifyingService(NotifierRouter router,
                                                       RetryMetrics metrics,
                                                       RetryNotifierProperties props) {
        RetryNotifierProperties.Async cfg = props.getAsync();
        ThreadPoolExecutor exec = new ThreadPoolExecutor(cfg.getCorePoolSize(),
                cfg.getMaxPoolSize(),
                cfg.getKeepAlive().toSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(cfg.getQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "retry-notify");
                    t.setDaemon(true);
                    t.setUncaughtExceptionHandler((th, e) -> LoggerFactory.getLogger("notify").error("uncaught", e));
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        RateLimitFilter filter = new RateLimitFilter(props.getRateLimit().getWindow(), props.getRateLimit().getThreshold());
        return new AsyncNotifyingService(exec, router, filter, metrics);
    }

    @Bean
    public NotifyingFacade notifyingFacade(ObjectProvider<AsyncNotifyingService> provider) {
        return new NotifyingFacade(provider);
    }
}
