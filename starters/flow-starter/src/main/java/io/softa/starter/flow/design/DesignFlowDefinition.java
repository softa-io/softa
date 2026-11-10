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

    @Schema(description = "Trigger definition (sealed TriggerSource sub-type): how an instance starts, and on the record "
            + "of which model — a record trigger names it (an Approval flow's is its document)")
    private TriggerSource trigger;

    @Schema(description = "Graph document (nodes + edges + viewport)")
    private FlowGraphDocument graph;

    @Schema(description = "Approval flows only: until when the initiator may withdraw an approval in progress; "
            + "unset = Never")
    private WithdrawPolicy withdrawPolicy;

    @Schema(description = "Approval flows only: whether its approvers may return the approval to the initiator, who "
            + "edits the record and submits it again — at any step. Unset = off")
    private Boolean returnEnabled;

    @Schema(description = "Approval flows only: whether the initiator may urge the approvers an approval waits on; "
            + "an administrator may either way. Unset = on")
    private Boolean urgeEnabled;

    @Schema(description = "Approval flows only: how often, in hours, each step an approval waits on may be urged, "
            + "by its initiator or an administrator. Unset = 4")
    private Integer urgeIntervalHours;

    @Schema(description = "Approval flows only: hours after a step opens that its approvers still to act are reminded, "
            + "then again every as many hours, at most 3 times; the step is due then. Unset = no reminders. A reminder "
            + "is all that happens: nothing is decided for anyone")
    private Integer remindAfterHours;

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

    @Schema(description = "What a step does when the record's submitter is one of its approvers (提交人是审批人时): "
            + "AutoApprove counts them as having approved, RequireApproval asks them like anyone else. The employee "
            + "the record is about, when someone else submitted it, is always asked like anyone else. Default "
            + "AutoApprove when unset.")
    private SelfApprovalStrategy selfApproval;

    @Schema(description = "Arbitrary metadata (ignored by the compiler)")
    private Map<String, Object> metadata;
}
