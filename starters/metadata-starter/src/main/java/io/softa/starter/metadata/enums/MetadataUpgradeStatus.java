package io.softa.starter.metadata.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * MetadataUpgradeHistory lifecycle status.
 * <p>
 * {@code RUNNING} → {@code SUCCESS} / {@code FAILURE}.
 * <p>
 * The runtime persists this state on every dispatched upgrade so the studio can poll
 * the outcome via {@code GET /upgrade/runtime/upgradeStatus} when the push callback
 * is lost. The status row is the runtime-side source of truth — the push callback is
 * only a best-effort latency optimisation.
 */
@Getter
@AllArgsConstructor
public enum MetadataUpgradeStatus {
    RUNNING("Running", "Upgrade is being applied on the runtime"),
    SUCCESS("Success", "Upgrade applied and metadata reloaded"),
    FAILURE("Failure", "Upgrade failed; partial apply may remain in place"),
    ;

    @JsonValue
    private final String status;

    private final String description;
}
