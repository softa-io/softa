package io.softa.starter.flow.runtime.bundle;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.design.FlowGraphPosition;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.NodeErrorConfig;

/**
 * Runtime node compiled from a design-time graph node.
 * <p>
 * Key changes from the previous model:
 * <ul>
 *   <li>{@code type} ({@link FlowNodeType}) replaces the former {@code kind} ({@code FlowNodeKind}),
 *       serving as the single discriminator for both handler routing and palette rendering.</li>
 *   <li>{@code errorConfig} ({@link NodeErrorConfig}) replaces the former loose pair of
 *       {@code errorStrategy} + {@code retryCount} fields.</li>
 *   <li>Return policy ({@code returnEnabled}, {@code returnTarget}, {@code returnTargetNodeId})
 *       is now embedded in {@code parsedConfig} as part of {@code ApprovalNodeConfig}.</li>
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

    @Schema(description = "Error handling configuration")
    @Builder.Default
    private NodeErrorConfig errorConfig = NodeErrorConfig.failFast();

    @Schema(description = "Incoming edge ids")
    private List<String> incomingEdgeIds;

    @Schema(description = "Outgoing edge ids")
    private List<String> outgoingEdgeIds;

    @Schema(description = "Original design-time canvas position")
    private FlowGraphPosition position;
}
