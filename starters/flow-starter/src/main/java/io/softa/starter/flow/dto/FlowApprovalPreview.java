package io.softa.starter.flow.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * What submitting a business record would do now, worked out without submitting it: the Submit flow
 * the record would get and the approval steps it would go through, each with the approvers who would
 * be asked or why it passes on its own. For a record whose approval is in progress, the rest of its path
 * instead: the step it waits on now, then those after it. Nothing is started or written. Users come with
 * their names.
 *
 * <p>The path assumes every step is approved. Nothing is guessed: a route that depends on data only
 * known once the approval runs stops the path where it is decided ({@link #undecided}), and a step
 * whose approvers depend on such data is {@link StepOutcome#UNDETERMINED}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowApprovalPreview")
public class FlowApprovalPreview {

    @Schema(description = "Whether submitting the record would start an approval now, or its approval is in progress")
    private Status status;

    @Schema(description = "Why no approval would start (Refused, NoFlow, SeveralFlows): the message the submission would be refused with")
    private String message;

    @Schema(description = "The flow that would approve the record (Ready), or approves it now (InProgress)")
    private Flow flow;

    @Schema(description = "The approval steps in the order the approval reaches them, every step assumed approved (Ready); "
            + "the step it waits on now, then those after it (InProgress)")
    private List<Step> steps;

    @Schema(description = "Where the route depends on data only known once the approval runs: the path shown stops there (Ready, InProgress)")
    private List<Stop> undecided;

    @Schema(description = "Where the approval would stop with an error, and why: the path shown ends there (Ready, InProgress)")
    private Stop failure;

    @Schema(description = "The nodes the route passes, in the order it reaches them, up to where it stops — from the step "
            + "it waits on now for an approval in progress (Ready, InProgress)")
    private List<String> nodeIds;

    @Schema(description = "Whether the requester may withdraw the record now: its submitter, while its approval waits on a "
            + "flow that lets them withdraw (InProgress), or after its approval failed (Refused)")
    private Boolean withdrawable;

    @Getter
    @AllArgsConstructor
    public enum Status {
        /** One flow applies: {@code flow} would approve the record along {@code steps}. */
        READY("Ready"),
        /** The record's approval is in progress: {@code steps} are the step it waits on now, then those after it. */
        IN_PROGRESS("InProgress"),
        /** The record is not a draft — approved, rejected, cancelled — or its own checks refuse a submission now. */
        REFUSED("Refused"),
        /** No Submit flow applies to the record. */
        NO_FLOW("NoFlow"),
        /** Several Submit flows apply to the record: a submission is refused until exactly one does. */
        SEVERAL_FLOWS("SeveralFlows");

        @JsonValue
        private final String type;
    }

    @Getter
    @AllArgsConstructor
    public enum StepOutcome {
        /** The step waits for the approvers listed. */
        WAITING("Waiting"),
        /**
         * The step passes on its own: approvers counted as having approved — the submitter, or someone
         * who approved an earlier step — meet its approval mode.
         */
        AUTO_APPROVED("AutoApproved"),
        /** Nobody resolves to the step, which is approved on record (empty-approver strategy AutoApprove). */
        NO_APPROVER_APPROVED("NoApproverApproved"),
        /** Nobody resolves to the step, which is skipped (empty-approver strategy Skip). */
        NO_APPROVER_SKIPPED("NoApproverSkipped"),
        /** Nobody resolves to the step, and the approval stops there with an error (empty-approver strategy Error). */
        NO_APPROVER_FAILS("NoApproverFails"),
        /** Who approves the step, or whether it passes on its own, depends on data only known once the approval runs. */
        UNDETERMINED("Undetermined");

        @JsonValue
        private final String type;
    }

    @Getter
    @AllArgsConstructor
    public enum AutoApprovalReason {
        /** The submitter is one of the step's approvers, and the flow approves for them. */
        SUBMITTER("Submitter"),
        /** The approver approved an earlier step, and the flow's approver dedup counts that here too. */
        EARLIER_APPROVAL("EarlierApproval");

        @JsonValue
        private final String type;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "FlowApprovalPreviewFlow")
    public static class Flow {

        @Schema(description = "Flow ID")
        private String flowId;

        @Schema(description = "Flow name")
        private String flowName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "FlowApprovalPreviewStep")
    public static class Step {

        @Schema(description = "Approval node id")
        private String nodeId;

        @Schema(description = "Approval node label")
        private String nodeLabel;

        @Schema(description = "Whether the step waits for approvers, passes on its own, or cannot be told yet")
        private StepOutcome outcome;

        @Schema(description = "The approvers who would be asked; empty when nobody would be or they are not known yet")
        private List<FlowRecordApprovalsView.Actor> approvers;

        @Schema(description = "How many of the approvers listed must approve, the automatic approvals counted; null when nobody would be asked")
        private Integer requiredApprovals;

        @Schema(description = "Approvers counted as having approved the step, and why")
        private List<AutoApproval> autoApprovals;

        @Schema(description = "Whether the approval waits on this step now (InProgress); its approvers are those still asked")
        private Boolean current;

        @Schema(description = "The roles the step goes to because it finds nobody (empty-approver strategy AssignToRole), by "
                + "name: its approvers are their holders, any one of whom decides. Null otherwise")
        private List<String> assignedRoles;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "FlowApprovalPreviewAutoApproval")
    public static class AutoApproval {

        @Schema(description = "The approver counted as having approved")
        private FlowRecordApprovalsView.Actor actor;

        @Schema(description = "Why they count as having approved")
        private AutoApprovalReason reason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "FlowApprovalPreviewStop")
    public static class Stop {

        @Schema(description = "Node id")
        private String nodeId;

        @Schema(description = "Node label")
        private String nodeLabel;

        @Schema(description = "Why the approval would stop here; set on a failure")
        private String message;
    }
}
