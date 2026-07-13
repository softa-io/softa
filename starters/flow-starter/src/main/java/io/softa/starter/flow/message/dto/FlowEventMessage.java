package io.softa.starter.flow.message.dto;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.framework.base.context.Context;

/**
 * Message DTO for asynchronous flow trigger events transported via Pulsar.
 */
@Data
@NoArgsConstructor
public class FlowEventMessage {

    /** Flow design id to start directly (optional — when null, trigger matching is used). */
    private Long designId;

    /** Pinned bundle id to start directly (optional; overrides {@link #designId} when set). */
    private Long bundleId;

    /** Trigger type string (maps to FlowTriggerType). */
    private String triggerType;

    /** Source model for entity-related triggers. */
    private String sourceModel;

    /** Source row id for entity-related triggers. */
    private String sourceRowId;

    /** Actor who triggered the event. */
    private String actorId;

    /** Parameters passed to the flow as initial variables. */
    private Map<String, Object> parameters;

    /** Event timestamp. */
    private LocalDateTime eventTime;

    /** Serialized user context for cross-thread propagation. */
    private Context context;
}

