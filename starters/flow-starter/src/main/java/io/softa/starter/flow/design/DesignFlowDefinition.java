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
import io.softa.starter.flow.enums.SelfApprovalStrategy;
import io.softa.starter.flow.enums.WithdrawPolicy;
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

    @Schema(description = "Display name")
    private String name;

    @Schema(description = "Execution scenario (replaces kind + purpose)")
    private FlowScenario scenario;

    @Schema(description = "The model whose records the flow starts on — an Approval flow's document, the model a change "
            + "trigger watches; empty when the flow starts on no record (a schedule, an API call, a parent flow). "
            + "Chosen when the flow is created and fixed: its nodes, conditions and field permissions read this model")
    private String modelName;

    @Schema(description = "Trigger definition (sealed TriggerSource sub-type): how an instance starts")
    private TriggerSource trigger;

    @Schema(description = "Graph document (nodes + edges + viewport)")
    private FlowGraphDocument graph;

    @Schema(description = "Form bindings for human-interaction nodes")
    private List<FlowFormBinding> forms;

    @Schema(description = "Approval flows only: until when the initiator may withdraw an approval in progress; "
            + "unset = Never")
    private WithdrawPolicy withdrawPolicy;

    @Schema(description = "Approval flows only: whether the initiator may urge the approvers an approval waits on; "
            + "an administrator may either way. Unset = on")
    private Boolean urgeEnabled;

    @Schema(description = "Approval flows only: how often, in minutes, each step an approval waits on may be urged, "
            + "by its initiator or an administrator. Unset = 240")
    private Integer urgeIntervalMinutes;

    @Schema(description = "Approval flows only: whether its notices are also emailed; unset = on")
    private Boolean emailNotification;

    @Schema(description = "Approval flows only: who is copied in as the approval is submitted, approved or rejected")
    private List<FlowCcRule> cc;

    @Schema(description = "Whether the flow executes synchronously within the triggering transaction")
    private Boolean sync;

    @Schema(description = "Whether to roll back the triggering transaction on flow failure (only when sync=true)")
    private Boolean rollbackOnFail;

    @Schema(description = "Approver consolidation policy (审批人去重): auto-approve a later node for an "
            + "actor who already approved this instance. Default GLOBAL when unset.")
    private ApproverDedupStrategy approverDedup;

    @Schema(description = "What a step does when the record's submitter, or the employee it is about, is one of its "
            + "approvers (本人是审批人时): AutoApprove counts them as having approved, RequireApproval asks them like "
            + "anyone else. Default AutoApprove when unset.")
    private SelfApprovalStrategy selfApproval;

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
