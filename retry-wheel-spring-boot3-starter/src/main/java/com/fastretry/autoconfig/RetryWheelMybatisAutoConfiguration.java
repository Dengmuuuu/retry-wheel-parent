package com.fastretry.autoconfig;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

import javax.sql.DataSource;

@AutoConfiguration
@ConditionalOnClass({
        SqlSessionFactory.class,
        MybatisSqlSessionFactoryBean.class
})
@ConditionalOnBean(DataSource.class)
@MapperScan(basePackages = "com.fastretry.mapper")
public class RetryWheelMybatisAutoConfiguration {
}
