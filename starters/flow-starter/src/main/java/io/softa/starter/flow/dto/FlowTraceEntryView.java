package io.softa.starter.flow.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;

/**
 * One persisted trace row, carrying its monotonic {@code sequence} so a running
 * instance can be polled incrementally ({@code ?sinceSequence=} fetches only
 * rows the client has not seen yet).
 */
@Schema(name = "FlowTraceEntryView")
public record FlowTraceEntryView(

        @Schema(description = "Monotonic position within the instance trace")
        Integer sequence,

        @Schema(description = "Node id; null for flow-level events")
        String nodeId,

        @Schema(description = "Node type when applicable")
        FlowNodeType nodeType,

        @Schema(description = "Trace event type")
        FlowTraceEventType eventType,

        @Schema(description = "Event timestamp")
        LocalDateTime eventTime,

        @Schema(description = "Free-form message")
        String message
) {
}
