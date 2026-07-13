package io.softa.starter.message.shared.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * Centralised Micrometer counters for {@code message-starter}.
 * <p>
 * Emits the following families (tags in parentheses):
 * <ul>
 *   <li>{@code softa.message.sent} (channel=mail/sms, provider)</li>
 *   <li>{@code softa.message.failed} (channel, provider, outcome=retry/failed/dead_letter)</li>
 *   <li>{@code softa.message.outbox.published} (route)</li>
 *   <li>{@code softa.message.outbox.dead} (route)</li>
 * </ul>
 * Gated on {@link MeterRegistry} being on the classpath so the starter stays
 * usable in stripped-down test contexts without actuator.
 */
@Component
@ConditionalOnClass(MeterRegistry.class)
public class MessageMetrics {

    private static final String SENT = "softa.message.sent";
    private static final String FAILED = "softa.message.failed";
    private static final String OUTBOX_PUBLISHED = "softa.message.outbox.published";
    private static final String OUTBOX_DEAD = "softa.message.outbox.dead";

    @Autowired
    private MeterRegistry registry;

    public void sent(String channel, String provider) {
        counter(SENT, Tags.of("channel", channel, "provider", safeTag(provider))).increment();
    }

    public void failed(String channel, String provider, String outcome) {
        counter(FAILED, Tags.of(
                "channel", channel,
                "provider", safeTag(provider),
                "outcome", outcome)).increment();
    }

    public void outboxPublished(String route) {
        counter(OUTBOX_PUBLISHED, Tags.of("route", route)).increment();
    }

    public void outboxDead(String route) {
        counter(OUTBOX_DEAD, Tags.of("route", route)).increment();
    }

    private Counter counter(String name, Tags tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private static String safeTag(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
