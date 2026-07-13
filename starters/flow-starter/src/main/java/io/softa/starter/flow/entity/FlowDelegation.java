package io.softa.starter.flow.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * Delegation rule for flow approval tasks.
 */
@Data
@Model(
    multiTenant = true,
    idStrategy = IdStrategy.DISTRIBUTED_LONG
)
@Index(indexName = "idx_tenant_delegator", fields = {"tenantId", "delegatorId"})
@Index(indexName = "idx_tenant_delegate_active", fields = {"tenantId", "delegateId", "active"})
@EqualsAndHashCode(callSuper = true)
public class FlowDelegation extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID", required = true)
    private Long tenantId;

    @Field(label = "Delegator ID", required = true, description = "Delegator actor id")
    private String delegatorId;

    @Field(label = "Delegate ID", required = true, description = "Delegate actor id")
    private String delegateId;

    @Field(length = 500)
    private String reason;

    @Field(description = "Delegation start time")
    private LocalDateTime startTime;

    @Field(description = "Delegation end time")
    private LocalDateTime endTime;

    @Field(description = "Whether the rule is active")
    private Boolean active;

    @Field(length = 30, description = "Delegation scope such as All, FlowCode or Node")
    private String scope;

    @Field(length = 100, description = "Flow code when the scope is flow-specific")
    private String flowCode;

    @Field(label = "Node ID", length = 100, description = "Node id when the scope is node-specific")
    private String nodeId;

    @Field(description = "Auto expire at end time")
    private Boolean autoExpire;

    @Field(copyable = false, description = "Delegated task count")
    private Integer delegatedTaskCount;

    @Field(copyable = false, description = "Last delegated time")
    private LocalDateTime lastDelegationTime;
}
