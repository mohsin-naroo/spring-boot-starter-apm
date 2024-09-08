package io.github.meritepk.apm.trace;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.tracing.Tracer;

@ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "true", matchIfMissing = true)
@Configuration
public class JdbcTraceConfiguration {

    @ConditionalOnProperty(name = "management.tracing.jdbc.enabled", havingValue = "true", matchIfMissing = true)
    @Bean
    public JdbcTraceAspect jdbcTraceAspect(Tracer tracer) {
        return new JdbcTraceAspect(tracer);
    }
}
