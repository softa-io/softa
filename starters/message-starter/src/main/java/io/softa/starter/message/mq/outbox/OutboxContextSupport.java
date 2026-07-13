package io.softa.starter.message.mq.outbox;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;

/**
 * Helpers that translate an {@link OutboxMessage} into a runtime
 * {@link Context} (tenant id + trace id) and run a body inside it.
 * <p>
 * Pulled out of the per-channel delivery consumers so the same idiomatic
 * pattern is used everywhere and adding a new outbox channel doesn't require
 * copying the {@code buildContext} stanza.
 * <p>
 * {@link io.softa.starter.message.mail.message.CronTaskMailFetchConsumer}
 * intentionally does not use this helper: it consumes a {@code CronTaskMessage}
 * (not an {@code OutboxMessage}) and uses the {@code @SwitchUser} contract
 * to set its system-user context.
 */
public final class OutboxContextSupport {

    private OutboxContextSupport() {}

    /** Build a tenant/trace {@link Context} from an outbox message. */
    public static Context buildContext(OutboxMessage message) {
        Context ctx = new Context();
        if (message.getTenantId() != null) {
            ctx.setTenantId(message.getTenantId());
        }
        if (message.getTraceId() != null) {
            ctx.setTraceId(message.getTraceId());
        }
        return ctx;
    }

    /**
     * Run {@code task} inside the tenant/trace context derived from
     * {@code message}. Restores the caller's previous context on exit.
     */
    public static void runWithContext(OutboxMessage message, Runnable task) {
        ContextHolder.runWith(buildContext(message), task);
    }
}
