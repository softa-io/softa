package io.softa.starter.flow.runtime.bundle;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.FlowNodeType;

/**
 * Runtime node compiled from a design-time graph node.
 * <p>
 * Key changes from the previous model:
 * <ul>
 *   <li>{@code type} ({@link FlowNodeType}) replaces the former {@code kind} ({@code FlowNodeKind}),
 *       serving as the single discriminator for both handler routing and palette rendering.</li>
 *   <li>Return policy ({@code returnEnabled}) is the flow's, on {@code CompiledFlowDefinition}, the same at every
 *       step.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "CompiledFlowNode")
public class CompiledFlowNode {

    @Schema(description = "Node id")
    private String nodeId;

    @Schema(description = "Node type (single discriminator for handler routing and palette)")
    private FlowNodeType type;

    @Schema(description = "Display label")
    private String label;

    @Schema(description = "Node config (raw map, persisted for serialisation)")
    private Map<String, Object> config;

    /**
     * Strongly-typed config parsed from {@link #config} at compile time.
     * Not serialised — rebuilt from {@link #config} on deserialisation.
     */
    @JsonIgnore
    @Schema(hidden = true)
    private Object parsedConfig;

    @Schema(description = "Incoming edge ids")
    private List<String> incomingEdgeIds;

    @Schema(description = "Outgoing edge ids, in the order a node that is not a gateway tries them: by priority, the "
            + "default last")
    private List<String> outgoingEdgeIds;

    @Schema(description = "On an inclusive gateway whose branches meet: the join they meet at, which counts the branches "
            + "the gateway does not take as arrived. Null when its branches each end on their own, and on every other "
            + "node")
    private String pairedJoinId;

    /** What a message calls the node: its label — the step's name people see — or its id when it has none. */
    public String displayName() {
        return label != null && !label.isBlank() ? label : nodeId;
    }
}
