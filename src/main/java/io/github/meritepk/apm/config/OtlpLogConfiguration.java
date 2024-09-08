package io.github.meritepk.apm.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.resources.Resource;

@Configuration
public class OtlpLogConfiguration {

    @ConditionalOnProperty(name = "management.otlp.logs.export.enabled", havingValue = "true", matchIfMissing = false)
    @Bean
    public LoggerProvider loggerProvider(Resource resource, @Value("${management.otlp.logs.export.url}") String url) {
        LogRecordExporter logExporter = OtlpGrpcLogRecordExporter.builder()
                .setEndpoint(url)
                .build();
        LogRecordProcessor logProcessor = BatchLogRecordProcessor.builder(logExporter)
                .setExporterTimeout(Duration.ofSeconds(1))
                .build();
        LoggerProvider loggerProvider = SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(logProcessor)
                .build();
        initOtelLogackAppender(loggerProvider);
        return loggerProvider;
    }

    private void initOtelLogackAppender(LoggerProvider loggerProvider) {
        if (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) instanceof ch.qos.logback.classic.Logger rootLogger
                && rootLogger.getAppender("FILE") instanceof RollingFileAppender<ILoggingEvent> fileAppender) {
            OtelLogackAppender appender = new OtelLogackAppender(loggerProvider, fileAppender.getEncoder());
            appender.setName("OTEL");
            appender.start();
            rootLogger.addAppender(appender);
        }
    }

    public static class OtelLogackAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

        private final LoggerProvider loggerProvider;
        private final Encoder<ILoggingEvent> encoder;

        public OtelLogackAppender(LoggerProvider loggerProvider, Encoder<ILoggingEvent> encoder) {
            this.loggerProvider = loggerProvider;
            this.encoder = encoder;
        }

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            LogRecordBuilder builder = loggerProvider.loggerBuilder(event.getLoggerName()).build().logRecordBuilder();
            builder.setBody(new String(encoder.encode(event), StandardCharsets.UTF_8));
            builder.setTimestamp(event.getTimeStamp(), TimeUnit.MILLISECONDS);
            Level level = event.getLevel();
            switch (level.levelInt) {
            case Level.ALL_INT, Level.TRACE_INT:
                builder.setSeverity(Severity.TRACE);
                builder.setSeverityText(level.levelStr);
                break;
            case Level.DEBUG_INT:
                builder.setSeverity(Severity.DEBUG);
                builder.setSeverityText(level.levelStr);
                break;
            case Level.INFO_INT:
                builder.setSeverity(Severity.INFO);
                builder.setSeverityText(level.levelStr);
                break;
            case Level.WARN_INT:
                builder.setSeverity(Severity.WARN);
                builder.setSeverityText(level.levelStr);
                break;
            case Level.ERROR_INT:
                builder.setSeverity(Severity.ERROR);
                builder.setSeverityText(level.levelStr);
                break;
            case Level.OFF_INT:
            default:
                builder.setSeverity(Severity.UNDEFINED_SEVERITY_NUMBER);
                builder.setSeverityText(level.levelStr);
                break;
            }
            builder.emit();
        }
    }
}
