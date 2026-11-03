package io.softa.starter.flow.design;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.FlowNodeType;

/**
 * Node definition in the design-time graph document.
 * <p>
 * {@code type} is now a typed {@link FlowNodeType} enum (the single discriminator),
 * replacing the former dual-key model of {@code kind} (FlowNodeKind) + optional
 * executor string. The xyflow/react {@code Node.type} field maps directly to
 * {@code FlowNodeType.getType()} string.
 * <p>
 * Approval return policy (formerly a separate {@code returnPolicy} field) is now
 * expressed within the {@code config} map as {@code returnEnabled} — a return always
 * goes to the initiator — matching the compiled {@code ApprovalNodeConfig} structure directly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowGraphNode")
public class FlowGraphNode {

    @Schema(description = "Stable node id")
    private String id;

    /**
     * Node type — both the xyflow/react component type key and the backend
     * execution discriminator. Serialises as the {@link FlowNodeType#getType()} string.
     */
    @Schema(description = "Node type (single discriminator for frontend and backend)")
    private FlowNodeType type;

    @Schema(description = "Node label")
    private String label;

    @Schema(description = "Node position on the canvas")
    private FlowGraphPosition position;

    @Schema(description = "Node width on the canvas")
    private Double width;

    @Schema(description = "Node height on the canvas")
    private Double height;

    @Schema(description = "Editor and execution config")
    private Map<String, Object> config;

    @Schema(description = "Opaque data bag passed to the frontend node component")
    private Map<String, Object> data;

    /** What a message calls the node: its label — the name people see — or its id when it has none. */
    public String displayName() {
        return label != null && !label.isBlank() ? label : id;
    }
}

