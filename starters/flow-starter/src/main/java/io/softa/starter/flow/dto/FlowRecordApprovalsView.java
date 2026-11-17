package io.softa.starter.flow.dto;

import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * A business record's approval — one per record, holding every submission of it: each the approval instance it
 * started, newest first, with its approval steps. A step is one the approval passed, one it waits on now, or — for
 * the submission in progress — one still ahead, as expected now. Whoever may see the record's approval sees every
 * submission of it. Users come with their names.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowRecordApprovalsView")
public class FlowRecordApprovalsView {

    @Schema(description = "The record's submissions — each the approval instance it started — newest first")
    private List<Instance> instances;

    /** Where a step stands in its approval. */
    @Getter
    @AllArgsConstructor
    public enum StepState {
        /**
         * The approval is past it: approved or auto-approved there, rejected or returned there, or left there when
         * the approval was withdrawn, terminated or failed — its approvers' results say which.
         */
        DONE("Done"),
        /** The approval waits on it now. */
        CURRENT("Current"),
        /** The approval has not reached it yet: expected from the route as it stands now. */
        UPCOMING("Upcoming");

        @JsonValue
        private final String type;
    }

    /** What an approver of a step did, does or is expected to do. */
    @Getter
    @AllArgsConstructor
    public enum ApproverResult {
        APPROVED("Approved"),
        REJECTED("Rejected"),
        RETURNED("Returned"),
        /** Handed the step to someone else, who has a row of their own. */
        TRANSFERRED("Transferred"),
        /** Delegated the step to someone else, who has a row of their own. */
        DELEGATED("Delegated"),
        /** Counted as having approved: the submitter, or someone who approved an earlier step. */
        AUTO_APPROVED("AutoApproved"),
        /** Asked, and has not acted yet. */
        PENDING("Pending"),
        /** No longer asked: others approved enough, or the approval was withdrawn or ended. */
        NOT_NEEDED("NotNeeded"),
        /** A step not reached yet: who is expected to be asked, as the route stands now. */
        EXPECTED("Expected");

        @JsonValue
        private final String type;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "FlowRecordApprovalInstance")
    public static class Instance {

        @Schema(description = "Runtime instance id")
        private String instanceId;

        @Schema(description = "Flow ID")
        private String flowId;

        @Schema(description = "Flow name")
        private String flowName;

        @Schema(description = "Instance status")
        private FlowExecutionStatus status;

        @Schema(description = "Whether a later submission of the record replaced this one, or the record was cancelled")
        private boolean superseded;

        @Schema(description = "Who submitted the record")
        private Actor initiator;

        @Schema(description = "When the instance started: when the record was submitted")
        private LocalDateTime startedAt;

        @Schema(description = "The approval steps in the order the approval reached them: those it passed, those it "
                + "waits on now, then — while it is in progress — those still ahead, as expected now")
        private List<Step> steps;

        @Schema(description = "While in progress: where the route ahead depends on data only known once the approval "
                + "runs — the expected steps stop there")
        private List<FlowApprovalPreview.Stop> undecided;

        @Schema(description = "While in progress: where the route ahead would stop with an error, and why — the "
                + "expected steps end there")
        private FlowApprovalPreview.Stop expectedFailure;

        @Schema(description = "Where and why the approval stopped, when it failed; its submitter withdraws the record to take it back")
        private Failure failure;

        @Schema(description = "How the approval ended — rejected, returned, withdrawn, terminated or completed; "
                + "null while it is in progress or after it failed")
        private Ending ended;

        @Schema(description = "Actions on the approval as a whole rather than on one step, such as an administrator "
                + "running the steps after an approval again")
        private List<Event> events;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "FlowRecordApprovalFailure")
    public static class Failure {

        @Schema(description = "Node the approval stopped at")
        private String nodeId;

        @Schema(description = "Label of that node")
        private String nodeLabel;

        @Schema(description = "Why it stopped")
        private String message;

        @Schema(description = "Whether it stopped in the steps after an approval: the approval stands, and an "
                + "administrator can run those steps again; otherwise its submitter takes the record back")
        private boolean retryable;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "FlowRecordApprovalStep")
    public static class Step {

        @Schema(description = "Approval node id")
        private String nodeId;

        @Schema(description = "Approval node label")
        private String nodeLabel;

        @Schema(description = "Passed, waited on now, or not reached yet")
        private StepState state;

        @Schema(description = "How many of the approvers must approve: any one, all, a count or a percentage")
        private VoteThresholdMode approvalMode;

        @Schema(description = "How many approvals pass the step, the automatic ones counted; null when nobody is asked")
        private Integer requiredApprovals;

        @Schema(description = "When the approval reached the step; null for one not reached yet")
        private LocalDateTime enteredAt;

        @Schema(description = "When the step was passed; null while it waits or before it is reached")
        private LocalDateTime finishedAt;

        @Schema(description = "Each approver with what they did, do or are expected to do; one who handed the step "
                + "on, and whom they handed it to, each have a row")
        private List<Approver> approvers;

        @Schema(description = "A step not reached yet: whether it is expected to wait for its approvers, pass on its "
                + "own, or cannot be told yet. A step passed with nobody to ask: NoApproverApproved. Null otherwise")
        private FlowApprovalPreview.StepOutcome outcome;

        @Schema(description = "The roles the step goes to because it finds nobody, by name: its approvers are their "
                + "holders, any one of whom decides. Null otherwise")
        private List<String> assignedRoles;

        @Schema(description = "Other actions on the step, in the order they happened: copies sent (and whether "
                + "read), urges and comments")
        private List<Event> events;
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "FlowRecordApprovalApprover")
    public static class Approver {

        @Schema(description = "The approver")
        private Actor actor;

        @Schema(description = "What they did, do or are expected to do")
        private ApproverResult result;

        @Schema(description = "Whom they transferred or delegated the step to")
        private Actor target;

        @Schema(description = "The administrator who acted in their place, such as by reassigning their task")
        private Actor operator;

        @Schema(description = "When they acted; null while they have not")
        private LocalDateTime time;

        @Schema(description = "Their comment")
        private String comment;

        @Schema(description = "Why they count as having approved, when they do (AutoApproved)")
        private FlowApprovalPreview.AutoApprovalReason autoApprovalReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "FlowRecordApprovalEvent")
    public static class Event {

        @Schema(description = "Action type: Cc, Urge, Comment, Retry — or AddSign in history recorded before it was removed")
        private ApprovalActionType action;

        @Schema(description = "Who acted")
        private Actor actor;

        @Schema(description = "Whom the action went to, such as a copy's recipient")
        private Actor target;

        @Schema(description = "The administrator who acted in the actor's place")
        private Actor operator;

        @Schema(description = "Comment")
        private String comment;

        @Schema(description = "When it happened")
        private LocalDateTime time;

        @Schema(description = "A copy: whether its recipient has read it. Null for other actions")
        private Boolean read;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "FlowRecordApprovalEnding")
    public static class Ending {

        @Schema(description = "The action that ended it — Reject, Return, Withdraw or Terminate; null when it "
                + "completed")
        private ApprovalActionType action;

        @Schema(description = "Who ended it; null when it completed")
        private Actor actor;

        @Schema(description = "The administrator who acted in the actor's place")
        private Actor operator;

        @Schema(description = "Comment")
        private String comment;

        @Schema(description = "When it ended")
        private LocalDateTime time;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "FlowRecordPendingStep")
    public static class PendingStep {

        @Schema(description = "Approval node id")
        private String nodeId;

        @Schema(description = "Approval node label")
        private String nodeLabel;

        @Schema(description = "Approvers who have not acted yet")
        private List<Actor> approvers;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "FlowRecordActor")
    public static class Actor {

        @Schema(description = "User id")
        private String id;

        @Schema(description = "Display name: the employee's name, else the account's nickname or username")
        private String name;
    }
}
