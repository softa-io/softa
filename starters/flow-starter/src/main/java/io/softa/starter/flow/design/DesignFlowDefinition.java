package io.softa.starter.flow.design;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.framework.orm.dto.DTOFieldObject;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.ApproverDedupStrategy;
import io.softa.starter.flow.enums.FlowScenario;

/**
 * Top-level design document submitted by the flow editor.
 * <p>
 * Key changes:
 * <ul>
 *   <li>{@code scenario} ({@link FlowScenario}) replaces the former {@code kind} +
 *       {@code purpose} two-dimensional matrix.</li>
 *   <li>{@code trigger} is a {@link TriggerSource} sealed-interface reference with a
 *       Jackson {@code @JsonTypeInfo} discriminator, replacing the former flat
 *       {@code FlowTriggerDefinition}.</li>
 *   <li>{@code declaredOutputs} lists the variable names this flow promises to produce
 *       (required for VALIDATION and COMPUTE scenarios).</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DesignFlowDefinition")
public class DesignFlowDefinition implements DTOFieldObject {

    @Schema(description = "Unique flow code")
    private String code;

    @Schema(description = "Display name")
    private String name;

    @Schema(description = "Execution scenario (replaces kind + purpose)")
    private FlowScenario scenario;

    @Schema(description = "Trigger definition (sealed TriggerSource sub-type)")
    private TriggerSource trigger;

    @Schema(description = "Graph document (nodes + edges + viewport)")
    private FlowGraphDocument graph;

    @Schema(description = "Form bindings for human-interaction nodes")
    private List<FlowFormBinding> forms;

    @Schema(description = "Whether the initiator can withdraw the flow before approval completes")
    private Boolean allowInitiatorWithdraw;

    @Schema(description = "Whether the flow executes synchronously within the triggering transaction")
    private Boolean sync;

    @Schema(description = "Whether to roll back the triggering transaction on flow failure (only when sync=true)")
    private Boolean rollbackOnFail;

    @Schema(description = "Approver consolidation policy (审批人去重): auto-approve a later node for an "
            + "actor who already approved this instance. Default GLOBAL when unset.")
    private ApproverDedupStrategy approverDedup;

    /**
     * Declared output variable names.
     * Required for VALIDATION and COMPUTE scenarios — tells the compiler what keys
     * to verify are produced by {@code ReturnValue} nodes.
     */
    @Schema(description = "Output variable names this flow promises to produce")
    private List<String> declaredOutputs;

    @Schema(description = "Arbitrary metadata (ignored by the compiler)")
    private Map<String, Object> metadata;
}
