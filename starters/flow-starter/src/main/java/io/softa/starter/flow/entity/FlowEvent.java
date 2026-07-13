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
 * Persistent trigger event log entry.
 * Records each time a flow trigger fires, capturing the trigger context,
 * matched flows, and execution outcomes for audit and debugging.
 */
@Data
@Model(
    multiTenant = true,
    idStrategy = IdStrategy.DISTRIBUTED_LONG,
    copyable = false
)
@Index(fields = {"tenantId", "flowCode"})
@Index(fields = {"tenantId", "instanceId"})
@Index(indexName = "idx_tenant_source", fields = {"tenantId", "sourceModel", "sourceRowId"})
@EqualsAndHashCode(callSuper = true)
public class FlowEvent extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID", required = true)
    private Long tenantId;

    @Field(length = 50,
            description = "Trigger type discriminator string (e.g., EntityChange, Api, Cron)")
    private String triggerType;

    @Field(length = 100,
            description = "Source model when the trigger is entity-related")
    private String sourceModel;

    @Field(label = "Source Row ID", description = "Source row id when the trigger is entity-related")
    private String sourceRowId;

    @Field(label = "Actor ID", description = "Actor who triggered the event")
    private String actorId;

    @Field(length = 100,
            description = "Flow code of the matched and started flow")
    private String flowCode;

    @Field(description = "Flow revision that was started")
    private Integer flowRevision;

    @Field(label = "Instance ID", description = "Runtime instance id of the started flow")
    private String instanceId;

    @Field(description = "Whether the flow was started successfully")
    private Boolean success;

    @Field(length = 2000, description = "Error message when the flow failed to start")
    private String errorMessage;

    @Field(length = 50,
            description = "Trigger fire method: fire, fireSyncOnly, fireAsyncOnly, fireForPurpose")
    private String fireMethod;

    @Field(description = "Event timestamp")
    private LocalDateTime eventTime;

    @Field(length = 100000, description = "Trigger parameters (JSON)")
    private String parameters;
}
