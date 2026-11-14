package io.softa.starter.flow.runtime.state;

import java.time.LocalDateTime;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * Structured audit entry for approval lifecycle actions.
 */
@Data
@Builder(toBuilder = true)
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

    @Schema(description = "Flow ID")
    private String flowId;

    @Schema(description = "Resolved flow revision")
    private Integer flowRevision;

    @Schema(description = "Primary node id for this action")
    private String nodeId;

    @Schema(description = "Primary node label for this action")
    private String nodeLabel;

    @Schema(description = "Action actor id when available")
    private String actorId;

    @Schema(description = "Optional action comment")
    private String comment;

    @Schema(description = "Execution status before the action")
    private FlowExecutionStatus statusBefore;

    @Schema(description = "Execution status after the action")
    private FlowExecutionStatus statusAfter;

    @Schema(description = "Approvers who had already approved when the action was recorded")
    private List<String> approvedActors;

    @Schema(description = "Required approval count for threshold-based approval modes")
    private Integer requiredApprovalCount;

    @Schema(description = "Target actor id when returning to initiator")
    private String targetActorId;

    @Schema(description = "Who did it on the actor's behalf: an administrator who reassigned the actor's task; "
            + "null when the actor acted themselves")
    private String operatorId;
}

