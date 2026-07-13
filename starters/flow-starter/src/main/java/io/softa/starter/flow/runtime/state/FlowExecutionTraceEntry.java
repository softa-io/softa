package io.softa.starter.flow.runtime.state;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.FlowNodeType;

/**
 * Trace record for runtime execution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowExecutionTraceEntry")
public class FlowExecutionTraceEntry {

    @Schema(description = "Flow code where the event happened")
    private String flowCode;

    @Schema(description = "Node id")
    private String nodeId;

    @Schema(description = "Node type")
    private FlowNodeType flowNodeType;

    @Schema(description = "Trace event type")
    private FlowTraceEventType eventType;

    @Schema(description = "Timestamp")
    private LocalDateTime eventTime;

    @Schema(description = "Message")
    private String message;
}
