package voldemort.tracer;

import io.jaegertracing.internal.JaegerTracer;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;

/**
 * Allows tracing internal service modules of Voldermort.
 * We start with Storage service for prtotyping trace since
 * that is the fundamental use-case of voldemort
 */
public class VoldemortTracer {

    /**
     * Helper to get global tracer
     */
    public static Tracer getTracer() {
        return GlobalTracer.get();
    }

    /**
     * Defines specific tracer based on config, if none is available then uses
     * {@code {@link io.opentracing.noop.NoopTracer}}.
     * Started when server starts up and need to initialized only once.
     *
     * @param service
     */
    public static void configure(final String service) {

        if(!GlobalTracer.isRegistered()) {
            //register a simple jaeger tracer
            GlobalTracer.register(new JaegerTracer.Builder(service).build());
        }
    }

    /*
     * Helper methods for starting span and scope
     */
    public static Scope activate(Span span, boolean finishSpanOnClose) {
        return getTracer().scopeManager().activate(span, finishSpanOnClose);
    }

    public static Span startSpan(String operationName) {
        return getTracer().buildSpan(operationName).start();
    }

    public static Tracer.SpanBuilder buildSpan(String operationName) {
        return getTracer().buildSpan(operationName);
    }

    public static Scope operationTrace(String name, final String key, final String operation) {
        Tracer.SpanBuilder spanBuilder = buildSpan(name)
                .withTag("operation-method", operation)
                .withTag("key", key);

        return spanBuilder.startActive(false);
    }

    public static ScopeManager scopeManager() {
        return getTracer().scopeManager();
    }
}
