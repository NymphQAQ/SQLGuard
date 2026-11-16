package io.sqlguard.starter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.DispatcherServlet;

@AutoConfiguration(after = SqlGuardAutoConfiguration.class)
@ConditionalOnClass({DispatcherServlet.class, HttpServletRequest.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(SqlGuardEngine.class)
public class SqlGuardWebAutoConfiguration {
    @Bean
    OncePerRequestFilter sqlGuardRequestScopeFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                try (SqlGuardScope ignored = SqlGuardScope.open()) {
                    chain.doFilter(request, response);
                }
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "sqlguard.management", name = "enabled", havingValue = "true")
    SqlGuardController sqlGuardController(SqlGuardEngine engine, SqlGuardProperties properties) {
        return new SqlGuardController(engine, properties.management().token());
    }
}
