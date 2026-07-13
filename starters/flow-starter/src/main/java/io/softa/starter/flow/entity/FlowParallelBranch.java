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
 * Persistent parallel branch execution record.
 * Tracks the execution status of each branch in a parallel gateway
 * for fine-grained auditing and diagnostics.
 */
@Data
@Model(
    multiTenant = true,
    idStrategy = IdStrategy.DISTRIBUTED_LONG,
    copyable = false
)
@Index(indexName = "idx_tenant_instance_fork", fields = {"tenantId", "instanceId", "forkNodeId"})
@EqualsAndHashCode(callSuper = true)
public class FlowParallelBranch extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID", required = true)
    private Long tenantId;

    @Field(label = "Runtime instance id", required = true)
    private String instanceId;

    @Field(label = "Fork node id (the parallel gateway node)", required = true, length = 100)
    private String forkNodeId;

    @Field(label = "Branch target node id", required = true, length = 100)
    private String branchNodeId;

    @Field(label = "Branch name or label", length = 200)
    private String branchName;

    @Field(label = "Branch status: PENDING, RUNNING, COMPLETED, FAILED")
    private FlowExecutionStatus status;

    @Field(label = "Branch start time")
    private LocalDateTime startTime;

    @Field(label = "Branch end time")
    private LocalDateTime endTime;

    @Field(label = "Duration in milliseconds")
    private Long durationMs;

    @Field(length = 2000, label = "Error message if the branch failed")
    private String errorMessage;

    @Field(length = 100000, label = "Branch result (JSON)")
    private String result;
}
