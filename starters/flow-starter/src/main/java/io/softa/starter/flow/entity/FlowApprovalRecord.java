package io.softa.starter.flow.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.flow.runtime.state.AddSignPosition;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Persistent approval audit record projection for flow runtime instances.
 */
@Data
@Model(
    multiTenant = true,
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        copyable = false
)
@Index(fields = {"instanceId", "sequence"}, unique = true)
@Index(fields = {"tenantId", "instanceId"})
@Index(indexName = "idx_tenant_actor_event", fields = {"tenantId", "actorId", "eventTime"})
@Index(indexName = "idx_tenant_target_event", fields = {"tenantId", "targetActorId", "eventTime"})
@EqualsAndHashCode(callSuper = true)
public class FlowApprovalRecord extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID", required = true)
    private Long tenantId;

    @Field(label = "Instance ID", required = true, description = "Runtime instance id")
    private String instanceId;

    @Field(length = 100, description = "Flow code")
    private String flowCode;

    @Field(description = "Published flow revision")
    private Integer flowRevision;

    @Field(label = "Node ID", length = 100, description = "Primary node id")
    private String nodeId;

    @Field(length = 200, description = "Primary node label")
    private String nodeLabel;

    @Field(description = "Approval task cycle number associated with the record when applicable")
    private Integer cycleNumber;

    @Field(label = "Task ID", description = "Task id when the record is attached to one task row")
    private Long taskId;

    @Field(required = true, description = "Monotonic runtime sequence within one instance")
    private Integer sequence;

    @Field(description = "Action type")
    private ApprovalActionType action;

    @Field(label = "Actor ID", description = "Operator actor id")
    private String actorId;

    @Field(label = "Target Actor ID", description = "Target actor id")
    private String targetActorId;

    @Field(label = "Add-Sign Position", description = "Add-sign position when the record represents an add-sign action")
    private AddSignPosition addSignPosition;

    @Field(label = "Target Node ID", length = 100, description = "Target node id")
    private String targetNodeId;

    @Field(length = 200, description = "Target node label")
    private String targetNodeLabel;

    @Field(length = 2000, description = "Comment")
    private String comment;

    @Field(description = "Status before action")
    private FlowExecutionStatus statusBefore;

    @Field(description = "Status after action")
    private FlowExecutionStatus statusAfter;

    @Field(length = 4000, description = "Actors who had already approved")
    private List<String> approvedActors;

    @Field(length = 4000, description = "Actors who had already rejected")
    private List<String> rejectedActors;

    @Field(length = 2000, description = "Updated variable keys")
    private List<String> variableKeys;

    @Field(description = "Recorded time")
    private LocalDateTime eventTime;
}
