package io.softa.starter.flow.dto;

import java.time.LocalDateTime;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * A business record's approval history, for its approval panel: every approval instance the
 * record went through, newest first — one per submission, a submission after a return included.
 * Users come with their names.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowRecordApprovalsView")
public class FlowRecordApprovalsView {

    @Schema(description = "The record's approval instances, newest first")
    private List<Instance> instances;

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

        @Schema(description = "Who submitted the record")
        private Actor initiator;

        @Schema(description = "When the instance started")
        private LocalDateTime startedAt;

        @Schema(description = "When the instance last changed")
        private LocalDateTime updatedAt;

        @Schema(description = "Steps waiting for approval, with the approvers yet to act; empty once the instance ended or was returned")
        private List<PendingStep> pending;

        @Schema(description = "Approval actions in the order they happened; CC reads are left out")
        private List<Action> actions;

        @Schema(description = "Where and why the approval stopped, when it failed; its submitter withdraws the record to take it back")
        private Failure failure;

        @Schema(description = "Whether the caller may open this approval — its detail, progress diagram and trace. "
                + "False for one they take no part in and their permissions do not reach: an approver of an earlier "
                + "submission sees the one in progress listed, but not inside it")
        private boolean viewable;
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
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "FlowRecordApprovalAction")
    public static class Action {

        @Schema(description = "Action type")
        private ApprovalActionType action;

        @Schema(description = "Label of the node the action was taken on")
        private String nodeLabel;

        @Schema(description = "Who acted")
        private Actor actor;

        @Schema(description = "Whom the action targeted: the new approver of a transfer or delegation, the added signer")
        private Actor target;

        @Schema(description = "Comment")
        private String comment;

        @Schema(description = "When the action happened")
        private LocalDateTime eventTime;
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
