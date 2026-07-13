package io.softa.starter.flow.runtime.state;

import java.time.LocalDateTime;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.ApprovalReturnTarget;
import io.softa.starter.flow.enums.VoteThresholdMode;

/**
 * Structured audit entry for approval lifecycle actions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "ApprovalActionAuditEntry")
public class ApprovalActionAuditEntry {

    @Schema(description = "Monotonic sequence number within one runtime instance")
    private Integer sequence;

    @Schema(description = "Approval lifecycle action type")
    private ApprovalActionType action;

    @Schema(description = "Audit event time")
    private LocalDateTime eventTime;

    @Schema(description = "Flow code")
    private String flowCode;

    @Schema(description = "Resolved flow revision")
    private Integer flowRevision;

    @Schema(description = "Primary node id for this action")
    private String nodeId;

    @Schema(description = "Primary node label for this action")
    private String nodeLabel;

    @Schema(description = "Approval task cycle number associated with this action when applicable")
    private Integer cycleNumber;

    @Schema(description = "Action actor id when available")
    private String actorId;

    @Schema(description = "Optional action comment")
    private String comment;

    @Schema(description = "Execution status before the action")
    private FlowExecutionStatus statusBefore;

    @Schema(description = "Execution status after the action")
    private FlowExecutionStatus statusAfter;

    @Schema(description = "Return target type when the action is Return")
    private ApprovalReturnTarget targetType;

    @Schema(description = "Approval mode of the node when applicable")
    private VoteThresholdMode approvalMode;

    @Schema(description = "Approvers who had already approved when the action was recorded")
    private List<String> approvedActors;

    @Schema(description = "Required approval count for threshold-based approval modes")
    private Integer requiredApprovalCount;

    @Schema(description = "Total configured approver count for the approval node")
    private Integer totalApproverCount;

    @Schema(description = "Whether approvers were resolved from a dynamic source")
    private Boolean dynamicApprovers;

    @Schema(description = "Reject mode of the node when applicable")
    private VoteThresholdMode rejectMode;

    @Schema(description = "Required reject count for threshold-based reject modes")
    private Integer requiredRejectCount;

    @Schema(description = "Approvers who had already rejected when the action was recorded")
    private List<String> rejectedActors;

    @Schema(description = "Target actor id when returning to initiator")
    private String targetActorId;

    @Schema(description = "Add-sign position when the action is AddSign")
    private AddSignPosition addSignPosition;

    @Schema(description = "Target node id when returning to a previous approval")
    private String targetNodeId;

    @Schema(description = "Target node label when returning to a previous approval")
    private String targetNodeLabel;

    @Schema(description = "Resubmission count after a resubmit action")
    private Integer resubmissionCount;

    @Schema(description = "Updated variable keys during a resubmit action")
    private List<String> variableKeys;
}

