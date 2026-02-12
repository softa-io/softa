package io.softa.starter.flow.message.dto;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.Data;

import io.softa.framework.base.context.Context;

/**
 * Flow event message
 */
@Data
public class FlowEventMessage {

    private Long flowId;
    private Long flowNodeId;
    private Boolean sync;
    private Boolean rollbackOnFail;
    private Long triggerId;
    private String sourceModel;
    private Serializable sourceRowId;
    private Map<String, Object> triggerParams;

    private LocalDateTime eventTime;
    private Context context;

    public FlowEventMessage() {
        this.eventTime = LocalDateTime.now();
    }
}
