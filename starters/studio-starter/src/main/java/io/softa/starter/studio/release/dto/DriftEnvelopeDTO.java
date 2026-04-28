package io.softa.starter.studio.release.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import io.softa.starter.studio.release.enums.DesignDriftCheckStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Envelope wrapping a drift report with the cache-side metadata (check status, error,
 * timestamp). UI consumers read {@code lastCheckedTime} to render a "last checked X min
 * ago" hint and decide whether to prompt the operator to hit Refresh, without a second
 * round-trip.
 * <p>
 * When the env has never been drift-checked, {@code lastCheckedTime} is null,
 * {@code checkStatus} is null, and {@code reports} is an empty list — the frontend
 * should treat this as "unknown" rather than "no drift".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DriftEnvelope", description = "Drift report plus cache metadata")
public class DriftEnvelopeDTO {

    @Schema(description = "Environment ID")
    private Long envId;

    @Schema(description = "Outcome of the last drift check; null when never checked")
    private DesignDriftCheckStatus checkStatus;

    @Schema(description = "Error message from the last failed check, null on success / never checked")
    private String errorMessage;

    @Schema(description = "When the last drift check completed; null when never checked")
    private LocalDateTime lastCheckedTime;

    @Schema(description = "Whether the last successful check found drift")
    private boolean hasDrift;

    @Schema(description = "Drift rows grouped by model; empty list when no drift / never checked")
    @Builder.Default
    private List<DriftReportDTO> reports = new ArrayList<>();
}
