package io.softa.starter.flow.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.state.ApprovalActionType;

/**
 * Persistent approval task projection for flow runtime instances.
 */
@Data
@Model(
    multiTenant = true,
    idStrategy = IdStrategy.DISTRIBUTED_LONG,
    copyable = false
)
@Index(indexName = "idx_tenant_instance_node", fields = {"tenantId", "instanceId", "nodeId"})
@Index(indexName = "idx_tenant_actor_status_start", fields = {"tenantId", "actorId", "status", "startTime"})
@Index(indexName = "idx_tenant_actor_status_end", fields = {"tenantId", "actorId", "status", "endTime"})
@EqualsAndHashCode(callSuper = true)
public class FlowApprovalTask extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID", required = true, description = "Tenant ID")
    private Long tenantId;

    @Field(label = "Instance ID", required = true, description = "Runtime instance id")
    private String instanceId;

    @Field(length = 100, description = "Flow code")
    private String flowCode;

    @Field(description = "Published flow revision")
    private Integer flowRevision;

    @Field(label = "Node ID", required = true, length = 100, description = "Approval node id")
    private String nodeId;

    @Field(length = 200, description = "Approval node label")
    private String nodeLabel;

    @Field(description = "Approval task cycle number for repeated visits to the same node")
    private Integer cycleNumber;

    @Field(label = "Actor ID", required = true, description = "Assigned actor id")
    private String actorId;

    @Field(required = true, description = "Task status")
    private FlowApprovalTaskStatus status;

    @Field(description = "Task type")
    private FlowApprovalTaskType taskType;

    @Field(description = "Latest action that changed this task")
    private ApprovalActionType action;

    @Field(length = 2000, description = "Latest action comment")
    private String comment;

    @Field(description = "Whether approvers were resolved dynamically")
    private Boolean dynamicApprovers;

    @Field(description = "Approval mode snapshot")
    private VoteThresholdMode approvalMode;

    @Field(description = "Required approval count snapshot")
    private Integer requiredApprovalCount;

    @Field(description = "Total approver count snapshot")
    private Integer totalApproverCount;

    @Field(description = "Reject mode snapshot")
    private VoteThresholdMode rejectMode;

    @Field(description = "Required reject count snapshot")
    private Integer requiredRejectCount;

    @Field(length = 4000, description = "Candidate actor ids for the node")
    private List<String> candidateActors;

    @Field(length = 4000, description = "Actors who already approved when this projection was synced")
    private List<String> approvedActors;

    @Field(length = 4000, description = "Actors who already rejected when this projection was synced")
    private List<String> rejectedActors;

    @Field(description = "Task opened time")
    private LocalDateTime startTime;

    @Field(description = "Task closed time")
    private LocalDateTime endTime;

    @Field(description = "Task due time for timeout handling")
    private LocalDateTime dueTime;

    @Field(description = "Remind count for overdue notifications")
    private Integer remindCount;

    @Field(length = 30, description = "Urgency level")
    private String urgency;

    @Field(label = "Batch ID", description = "Batch ID for batch approval operations")
    private Long batchId;

    @Field(length = 100000, description = "Form data snapshot at the time the task was created (JSON)")
    private String formSnapshot;

    @Field(label = "Closed By Actor ID", description = "Actor who closed this task when available")
    private String closedByActorId;

    @Field(description = "Whether the task is blocked by an add-sign-before prerequisite")
    private Boolean blocked;

    @Field(label = "Blocked By Actor ID", description = "Actor who must act before this blocked task can proceed")
    private String blockedByActorId;
}
