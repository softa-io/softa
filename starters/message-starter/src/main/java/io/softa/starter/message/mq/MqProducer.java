package io.softa.starter.message.mq;

import java.util.concurrent.CompletableFuture;

/**
 * Broker-agnostic message producer abstraction.
 * <p>
 * Keeps the rest of the code-base free of Pulsar specifics (topic strings,
 * {@code PulsarTemplate}, {@code deliverAfter}). Swap in a Kafka implementation
 * by providing another {@link MqProducer} bean.
 * <p>
 * Payloads are always routed with a {@link TopicRoute} so tenants can swap
 * logical topic identities without touching call sites.
 */
public interface MqProducer {

    /**
     * Publish asynchronously. Returns a future that completes when the broker
     * acknowledges the message. Delayed delivery is handled by the outbox
     * ({@code next_attempt_at}), not the broker, so no synchronous or
     * broker-delayed variant is needed.
     */
    CompletableFuture<Void> sendAsync(TopicRoute route, Object payload);

    /**
     * @return true iff the underlying broker client is wired up and the route's
     *         topic is configured. Callers should check this before pushing on
     *         hot paths; the outbox publisher tolerates {@code false} by
     *         leaving the entry in {@code NEW} status until the broker returns.
     */
    boolean isAvailable(TopicRoute route);
}
