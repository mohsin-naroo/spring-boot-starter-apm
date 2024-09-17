package io.github.meritepk.apm.trace;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Tracer.SpanInScope;

@Aspect
public class JdbcTraceAspect {

    private static final String DB_STATEMENT = "db.statement";
    private static final String DB_STATEMENT_BATCH = "db.statement.batch";
    private static final String DB_STATEMENT_PARAMS = "db.statement.params";
    private static final String ERROR = "error";

    private final Logger logger = LoggerFactory.getLogger(JdbcTraceAspect.class);

    private final Tracer tracer;

    public JdbcTraceAspect(Tracer aTracer) {
        this.tracer = aTracer;
    }

    @Pointcut("target(javax.sql.DataSource)")
    public void datasourcePointcut() {
        // datasource pointcut
    }

    @Around("datasourcePointcut()")
    public Object traceAdviceDataSource(ProceedingJoinPoint invocation) throws Throwable {
        return invokeDataSource(invocation);
    }

    private Object invokeDataSource(ProceedingJoinPoint invocation) throws Throwable {
        Object retVal;
        if ("getConnection".equals(invocation.getSignature().getName())) {
            Span span = tracer.nextSpan().name("DataSource.getConnection");
            span = span.start();
            try (SpanInScope scope = tracer.withSpan(span)) {
                retVal = invocation.proceed();
            } finally {
                span.end();
            }
        } else {
            retVal = invocation.proceed();
        }
        if (retVal instanceof Connection target && tracer.currentSpan() != null) {
            retVal = proxyConnection(target);
        }
        return retVal;
    }

    private Object proxyConnection(Connection connection) {
        JdbcTraceContext jdbcTraceContext = JdbcTraceContext.of(connection, "Connection", null);
        return Proxy.newProxyInstance(connection.getClass().getClassLoader(), new Class<?>[] { Connection.class },
                invokeConnection(jdbcTraceContext));
    }

    private InvocationHandler invokeConnection(JdbcTraceContext context) {
        return (proxy, method, args) -> {
            try {
                Object retVal;
                switch (method.getName()) {
                case "prepareStatement":
                    retVal = proxyPreparedStatement((PreparedStatement) method.invoke(context.target, args),
                            (String) args[0]);
                    break;
                case "close":
                    retVal = invokeWithTrace(context, method, args);
                    break;
                case "commit":
                    retVal = invokeWithTrace(context, method, args);
                    break;
                case "rollback":
                    retVal = invokeWithTrace(context, method, args);
                    break;
                case "createStatement":
                    retVal = proxyStatement((Statement) method.invoke(context.target, args));
                    break;
                case "prepareCall":
                    retVal = proxyCallableStatement((CallableStatement) method.invoke(context.target, args),
                            (String) args[0]);
                    break;
                default:
                    retVal = method.invoke(context.target, args);
                }
                return retVal;
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        };
    }

    private Object proxyStatement(Statement statement) {
        JdbcTraceContext jdbcTraceContext = JdbcTraceContext.of(statement, "Statement", null);
        return Proxy.newProxyInstance(statement.getClass().getClassLoader(), new Class<?>[] { Statement.class },
                invokeStatement(jdbcTraceContext));
    }

    private InvocationHandler invokeStatement(JdbcTraceContext context) {
        return (proxy, method, args) -> {
            try {
                Object retVal;
                if (method.getName().startsWith("execute")) {
                    if (!method.getName().endsWith("Batch")) {
                        context.sql = (String) args[0];
                    }
                    retVal = invokeWithTrace(context, method, args);
                } else if (method.getName().startsWith("addBatch")) {
                    if (context.batch == 0) {
                        context.sql = (String) args[0];
                    }
                    context.batch++;
                    retVal = method.invoke(context.target, args);
                } else {
                    retVal = method.invoke(context.target, args);
                }
                return retVal;
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        };
    }

    private Object proxyPreparedStatement(PreparedStatement statement, String sql) {
        JdbcTraceContext jdbcTraceContext = JdbcTraceContext.of(statement, "PreparedStatement", sql);
        return Proxy.newProxyInstance(statement.getClass().getClassLoader(), new Class<?>[] { PreparedStatement.class },
                invokePreparedStatement(jdbcTraceContext));
    }

    private InvocationHandler invokePreparedStatement(JdbcTraceContext context) {
        return (proxy, method, args) -> {
            try {
                Object retVal;
                if (method.getName().startsWith("execute")) {
                    retVal = invokeWithTrace(context, method, args);
                } else if (method.getName().startsWith("addBatch")) {
                    context.batch++;
                    retVal = method.invoke(context.target, args);
                } else {
                    if (args != null && args.length > 1 && context.batch == 0 && method.getName().startsWith("set")) {
                        addParameter(context, method, args);
                    }
                    retVal = method.invoke(context.target, args);
                }
                return retVal;
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        };
    }

    private void addParameter(JdbcTraceContext context, Method method, Object[] args) {
        if (context.params == null) {
            context.params = new StringBuilder();
        }
        if (method.getName().equals("setNull")) {
            context.params.append(args[0]).append(':').append("null").append(';');
        } else {
            context.params.append(args[0]).append(':').append(args[1]).append(';');
        }
    }

    private Object proxyCallableStatement(CallableStatement statement, String sql) {
        JdbcTraceContext jdbcTraceContext = JdbcTraceContext.of(statement, "CallableStatement", sql);
        return Proxy.newProxyInstance(statement.getClass().getClassLoader(), new Class<?>[] { CallableStatement.class },
                invokePreparedStatement(jdbcTraceContext));
    }

    private Object invokeWithTrace(JdbcTraceContext context, Method method, Object[] args)
            throws InvocationTargetException {
        Span span = tracer.nextSpan().name(String.join(".", context.type, method.getName()));
        if (context.sql != null) {
            span.tag(DB_STATEMENT, context.sql);
        }
        if (context.batch > 0) {
            span.tag(DB_STATEMENT_BATCH, context.batch);
        }
        if (context.params != null && context.params.length() > 0) {
            span.tag(DB_STATEMENT_PARAMS, context.params.toString());
        }
        span = span.start();
        try (SpanInScope scope = tracer.withSpan(span)) {
            return method.invoke(context.target, args);
        } catch (InvocationTargetException ex) {
            onError(span, ex.getTargetException());
            throw ex;
        } catch (Throwable ex) {
            onError(span, ex);
            throw new InvocationTargetException(ex);
        } finally {
            span.end();
        }
    }

    private void onError(Span span, Throwable ex) {
        String message = ex.toString();
        logger.warn("error: {}", message);
        span.tag(ERROR, true).tag("exception", message);
    }

    private static class JdbcTraceContext {
        private Object target;
        private String type;
        private String sql;
        private int batch;
        private StringBuilder params;

        public static JdbcTraceContext of(Object target, String type, String sql) {
            JdbcTraceContext call = new JdbcTraceContext();
            call.target = target;
            call.type = type;
            call.sql = sql;
            return call;
        }
    }
}
