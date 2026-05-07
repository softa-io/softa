package io.softa.starter.metadata.dto;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.metadata.entity.MetadataUpgradeHistory;
import io.softa.starter.metadata.enums.MetadataUpgradeStatus;

/**
 * Wire DTO for {@code GET /upgrade/runtime/upgradeStatus}. Carries only the fields
 * the studio needs to reconcile a deployment — internal columns (audit trail,
 * package summary) stay on the runtime side.
 */
@Data
@NoArgsConstructor
public class MetadataUpgradeHistoryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String callbackToken;

    private MetadataUpgradeStatus status;

    private String errorMessage;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Double durationTime;

    public static MetadataUpgradeHistoryDTO from(MetadataUpgradeHistory entity) {
        if (entity == null) {
            return null;
        }
        MetadataUpgradeHistoryDTO dto = new MetadataUpgradeHistoryDTO();
        dto.setCallbackToken(entity.getCallbackToken());
        dto.setStatus(entity.getStatus());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setStartTime(entity.getStartTime());
        dto.setEndTime(entity.getEndTime());
        dto.setDurationTime(entity.getDurationTime());
        return dto;
    }
}
