package io.sqlguard.starter;

import org.apache.ibatis.plugin.Interceptor;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = MybatisAutoConfiguration.class)
@ConditionalOnClass(Interceptor.class)
@ConditionalOnProperty(prefix = "sqlguard", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SqlGuardProperties.class)
public class SqlGuardAutoConfiguration {
    @Bean
    SqlGuardEngine sqlGuardEngine(SqlGuardProperties properties, ObjectProvider<SqlGuardReporter> reporters) {
        return new SqlGuardEngine(properties, reporters.orderedStream().toList());
    }

    @Bean
    Interceptor sqlGuardInterceptor(SqlGuardEngine engine, SqlGuardProperties properties) {
        return new SqlCaptureInterceptor(engine, properties);
    }
}
