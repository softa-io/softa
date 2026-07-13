package io.softa.starter.flow.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Persistent flow execution instance entity.
 * Complex nested runtime state fields are stored as JSON strings.
 */
@Data
@Model(
    multiTenant = true,
    idStrategy = IdStrategy.DISTRIBUTED_LONG,
    versionLock = true,
    copyable = false
)
@Index(indexName = "uk_instance_id", fields = {"instanceId"}, unique = true)
@Index(indexName = "idx_tenant_flow_status", fields = {"tenantId", "flowCode", "status"})
@Index(indexName = "idx_tenant_status_fire", fields = {"tenantId", "status", "nextFireAt"})
@Index(indexName = "idx_tenant_initiator_status", fields = {"tenantId", "initiatorId", "status"})
@Index(indexName = "idx_tenant_model_row", fields = {"tenantId", "modelName", "rowId"})
@Index(indexName = "idx_tenant_design", fields = {"tenantId", "designId"})
@EqualsAndHashCode(callSuper = true)
public class FlowInstance extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID", required = true)
    private Long tenantId;

    @Field(description = "Optimistic-lock version for runtime state updates", required = true, defaultValue = "1")
    private Integer version;

    @Field(label = "Instance ID", required = true, description = "Runtime instance id (UUID)")
    private String instanceId;

    @Field(label = "Bundle ID", description = "Exact FlowBundle.id this instance was created from; "
            + "required to resolve the definition for approval/resume actions after a DB reload")
    private Long bundleId;

    @Field(label = "Design ID", description = "Source FlowDesign.id (stable flow handle)")
    private Long designId;

    @Field(length = 100, required = true, description = "Flow code")
    private String flowCode;

    @Field(description = "Published flow revision")
    private Integer flowRevision;

    @Field(length = 200, description = "Instance title")
    private String title;

    @Field(length = 100, description = "Related model name")
    private String modelName;

    @Field(label = "Row ID", description = "Related row data ID")
    private String rowId;

    @Field(label = "Initiator ID", description = "Flow initiator id")
    private String initiatorId;

    @Field(required = true, description = "Execution status")
    private FlowExecutionStatus status;

    @Field(description = "Resubmission count after return")
    private Integer resubmissionCount;

    @Field(length = 2000, description = "Error message when execution fails")
    private String errorMessage;

    @Field(label = "Failed Node ID", length = 100, description = "Node where execution failed (set when status = Failed)")
    private String failedNodeId;

    @Field(length = 100000, description = "Immutable trigger payload (JSON)")
    private String inputPayload;

    @Field(length = 100000, description = "Execution variables (JSON)")
    private String variables;

    @Field(length = 100000, description = "Active timer/async waits (JSON array); pending approvals are tracked separately")
    private String waitTokens;

    @Field(description = "Earliest due time across timer waits (denormalized from waitTokens for the sweep index)")
    private LocalDateTime nextFireAt;

    @Field(label = "Completed Node IDs", length = 100000, description = "Completed node ids (JSON array)")
    private String completedNodeIds;

    @Field(length = 100000, description = "Pending approvals (JSON array)")
    private String pendingApprovals;

    @Field(length = 100000, description = "Returned approval context (JSON)")
    private String returnedApproval;

    @Field(length = 100000, description = "Parallel join arrival counts (JSON map)")
    private String joinArrivalCounts;

    @Field(length = 100000, description = "Return data from ReturnValue nodes (JSON)")
    private String returnData;
}
