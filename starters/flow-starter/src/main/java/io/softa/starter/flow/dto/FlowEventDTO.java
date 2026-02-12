package io.softa.starter.flow.dto;

import java.io.Serializable;
import java.util.Map;
import lombok.Data;

/**
 * Flow event DTO for simulation
 */
@Data
public class FlowEventDTO {
    private Long flowId;
    private Long flowNodeId;
    private Boolean rollbackOnFail;
    private Long triggerId;
    private String sourceModel;
    private Serializable sourceRowId;
    private Map<String, Object> triggerParams;
}
