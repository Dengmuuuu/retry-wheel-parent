package com.fastretry.autoconfig;

import com.fastretry.config.RetryWheelProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration
@EnableConfigurationProperties(RetryWheelProperties.class)
@ConditionalOnClass({TransactionTemplate.class, PlatformTransactionManager.class})
@ConditionalOnBean(PlatformTransactionManager.class)
public class RetryTxAutoConfiguration {

    /** 业务未定义 TransactionTemplate 时，提供一个默认的编程式事务模板 */
    @Bean
    @ConditionalOnMissingBean(TransactionTemplate.class)
    public TransactionTemplate transactionTemplate(PlatformTransactionManager tm,
                                                   RetryWheelProperties props) {
        TransactionTemplate tpl = new TransactionTemplate(tm);
        tpl.setPropagationBehavior(props.getTx().getPropagation().value());
        tpl.setIsolationLevel(props.getTx().getIsolation().value());
        tpl.setReadOnly(props.getTx().isReadOnly());
        if (props.getTx().getTimeoutSeconds() > 0) {
            tpl.setTimeout(props.getTx().getTimeoutSeconds());
        }
        return tpl;
    }
}
