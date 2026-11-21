package io.softa.starter.sentry;

import java.util.Map;
import io.sentry.EventProcessor;
import io.sentry.Hint;
import io.sentry.SentryBaseEvent;
import io.sentry.SentryEvent;
import io.sentry.protocol.SentryTransaction;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.MdcKeys;

/**
 * Copies the softa {@link Context} identity (traceId / tenantId / userId) onto every
 * Sentry event and transaction as searchable tags, so issues can be filtered by tenant
 * and cross-referenced with log lines carrying the same traceId. MDC entries named
 * {@code tag.<name>} ({@link MdcKeys#TAG_PREFIX}) become the tag {@code <name>} as well.
 *
 * <p>EventProcessor beans are collected automatically by sentry-spring-boot's
 * auto-configuration. Events captured outside a bound context (startup, schedulers,
 * MQ consumers) are left untouched — {@code existContext()} guards against tagging
 * the synthetic Context (with its freshly generated traceId) that
 * {@code ContextHolder.getContext()} fabricates for unbound threads.
 */
@Component
public class SentryContextEnricher implements EventProcessor {

    @Override
    public SentryEvent process(@NonNull SentryEvent event, @NonNull Hint hint) {
        enrich(event);
        return event;
    }

    @Override
    public SentryTransaction process(@NonNull SentryTransaction transaction, @NonNull Hint hint) {
        enrich(transaction);
        return transaction;
    }

    private void enrich(SentryBaseEvent event) {
        tagFromMdc(event);
        if (!ContextHolder.existContext()) {
            return;
        }
        Context ctx = ContextHolder.getContext();
        if (ctx.getTraceId() != null) {
            event.setTag("trace_id", ctx.getTraceId());
        }
        if (ctx.getTenantId() != null) {
            event.setTag("tenant_id", String.valueOf(ctx.getTenantId()));
        }
        if (ctx.getUserId() != null) {
            event.setTag("user_id", String.valueOf(ctx.getUserId()));
        }
    }

    /**
     * MDC entries named {@code tag.<name>} become the tag {@code <name>}. Event processors run on
     * the thread that captured the event — for a log-based event, the thread that logged — so the
     * entries the code put around its log call are still there.
     */
    private static void tagFromMdc(SentryBaseEvent event) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        if (mdc == null) {
            return;
        }
        mdc.forEach((key, value) -> {
            if (key.startsWith(MdcKeys.TAG_PREFIX) && key.length() > MdcKeys.TAG_PREFIX.length()
                    && value != null && !value.isEmpty()) {
                event.setTag(key.substring(MdcKeys.TAG_PREFIX.length()), value);
            }
        });
    }
}
