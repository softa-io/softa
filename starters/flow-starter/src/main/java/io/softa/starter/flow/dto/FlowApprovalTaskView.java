package io.softa.starter.flow.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * API view of an approval task query result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowApprovalTaskView")
public class FlowApprovalTaskView {

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Runtime instance id")
    private String instanceId;

    @Schema(description = "Flow code")
    private String flowCode;

    @Schema(description = "Published flow revision")
    private Integer flowRevision;

    @Schema(description = "Approval node id")
    private String nodeId;

    @Schema(description = "Approval node label")
    private String nodeLabel;

    @Schema(description = "Approval task cycle number for repeated visits to the same node")
    private Integer cycleNumber;

    @Schema(description = "Assigned actor id")
    private String actorId;

    @Schema(description = "Task status")
    private FlowApprovalTaskStatus status;

    @Schema(description = "Task type")
    private FlowApprovalTaskType taskType;

    @Schema(description = "Latest action that changed this task")
    private ApprovalActionType action;

    @Schema(description = "Latest action comment")
    private String comment;

    @Schema(description = "Whether approvers were resolved dynamically")
    private Boolean dynamicApprovers;

    @Schema(description = "Approval mode snapshot")
    private VoteThresholdMode approvalMode;

    @Schema(description = "Required approval count snapshot")
    private Integer requiredApprovalCount;

    @Schema(description = "Total approver count snapshot")
    private Integer totalApproverCount;

    @Schema(description = "Reject mode snapshot")
    private VoteThresholdMode rejectMode;

    @Schema(description = "Required reject count snapshot")
    private Integer requiredRejectCount;

    @Schema(description = "Candidate actor ids for the node")
    private List<String> candidateActors;

    @Schema(description = "Actors who already approved when this projection was synced")
    private List<String> approvedActors;

    @Schema(description = "Actors who already rejected when this projection was synced")
    private List<String> rejectedActors;

    @Schema(description = "Task opened time")
    private LocalDateTime startTime;

    @Schema(description = "Task closed time")
    private LocalDateTime endTime;

    @Schema(description = "Actor who closed this task when available")
    private String closedByActorId;

    @Schema(description = "Whether the task is blocked by an unresolved add-sign dependency")
    private Boolean blocked;

    @Schema(description = "Actor who must act before this blocked task can proceed")
    private String blockedByActorId;

    @Schema(description = "Approval deadline derived from the node's timeout config; null = no deadline")
    private LocalDateTime dueTime;

    @Schema(description = "Urgency marker propagated from urge actions")
    private String urgency;

    @Schema(description = "Business-form snapshot frozen when the task materialized: the node's "
            + "non-hidden formPermissions fields with the bound row's values at that moment. "
            + "Null when the node declares no form fields (or the design predates them)")
    private Map<String, Object> formSnapshot;

    @Schema(description = "Title of the owning instance (batch-enriched; null when the instance row is gone)")
    private String instanceTitle;

    @Schema(description = "Business model bound to the owning instance")
    private String modelName;

    @Schema(description = "Business row id bound to the owning instance")
    private String rowId;

    @Schema(description = "Current execution status of the owning instance")
    private FlowExecutionStatus instanceStatus;
}

