package io.softa.starter.studio.release.dto;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.framework.orm.enums.AccessType;

/**
 * DTO for row change information
 */
@Data
@NoArgsConstructor
public class RowChangeDTO {

    private String model;
    private Long rowId;
    private AccessType accessType;

    // Aggregated old values for the fields touched by this change set
    private Map<String, Object> dataBeforeChange = new HashMap<>();
    // Aggregated new values for the fields touched by this change set
    private Map<String, Object> dataAfterChange = new HashMap<>();
    // Full effective row snapshot used by release, DDL, and deployment workflows
    private Map<String, Object> currentData = new HashMap<>();

    private Long lastChangedById;
    private String lastChangedBy;
    private String lastChangedTime;

    public RowChangeDTO(String model, Long rowId) {
        this.model = model;
        this.rowId = rowId;
    }

    public void mergeDataBeforeChange(Map<String, Object> newValue) {
        if (newValue != null) {
            this.dataBeforeChange.putAll(newValue);
        }
    }

    public void mergeDataAfterChange(Map<String, Object> newValue) {
        if (newValue != null) {
            this.dataAfterChange.putAll(newValue);
        }
    }

}
