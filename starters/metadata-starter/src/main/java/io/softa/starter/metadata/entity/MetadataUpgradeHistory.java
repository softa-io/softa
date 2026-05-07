package io.softa.starter.metadata.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.metadata.enums.MetadataUpgradeStatus;

/**
 * MetadataUpgradeHistory — runtime-side persistent record of every metadata upgrade
 * dispatched by a studio.
 * <p>
 * Acts as the source of truth for upgrade outcomes when the push callback to the
 * studio is lost (network blip, studio restart, JVM crash). The studio can fall back
 * to {@code GET /upgrade/runtime/upgradeStatus?callbackToken=...} to reconcile a
 * stuck deployment.
 * <p>
 * Lifecycle: {@code RUNNING} → {@code SUCCESS} / {@code FAILURE}.
 */
@Data
@Schema(name = "MetadataUpgradeHistory")
@EqualsAndHashCode(callSuper = true)
public class MetadataUpgradeHistory extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Env ID")
    private Long envId;

    @Schema(description = "Callback Token")
    private String callbackToken;

    @Schema(description = "Status")
    private MetadataUpgradeStatus status;

    @Schema(description = "Error Message")
    private String errorMessage;

    @Schema(description = "Execution Start Time")
    private LocalDateTime startTime;

    @Schema(description = "Execution End Time")
    private LocalDateTime endTime;

    @Schema(description = "Duration Time (s)")
    private Double durationTime;

    @Schema(description = "Package Summary")
    private JsonNode packageSummary;
}
