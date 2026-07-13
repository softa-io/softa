package io.softa.starter.message.mq.outbox;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Lifecycle of an outbox entry.
 * <p>
 *   NEW → PUBLISHING         (claimed by OutboxPublisher)
 *   PUBLISHING → PUBLISHED   (happy path)
 *   PUBLISHING → NEW         (publish failed / stale claim recovered)
 *   PUBLISHING → DEAD        (exceeded max publish attempts)
 */
@Getter
@AllArgsConstructor
public enum OutboxStatus {
    NEW("New", "Not yet published to broker"),
    PUBLISHING("Publishing", "Claimed by a publisher instance"),
    PUBLISHED("Published", "Successfully published; kept for audit / replay"),
    DEAD("Dead", "Broker failure beyond retry budget — needs manual intervention");

    @JsonValue
    private final String code;
    private final String description;
}
