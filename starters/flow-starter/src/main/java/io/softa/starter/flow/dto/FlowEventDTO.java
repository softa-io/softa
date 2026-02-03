package io.softa.starter.flow.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * Flow event DTO for simulation
 */
@Data
public class FlowEventDTO {
    private String flowId;
    private String flowNodeId;
    private Boolean rollbackOnFail;
    private String triggerId;
    private String sourceModel;
    private Serializable sourceRowId;
    private Map<String, Object> triggerParams;
}
