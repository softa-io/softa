package io.softa.starter.flow.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.flow.enums.CcTiming;

/**
 * Configurable CC (carbon copy) rule for a flow or specific node.
 */
@Data
@Model(
    multiTenant = true,
    label = "Flow CC Config",
    idStrategy = IdStrategy.DISTRIBUTED_LONG
)
@Index(indexName = "idx_tenant_flow_active", fields = {"tenantId", "flowCode", "active"})
@EqualsAndHashCode(callSuper = true)
public class FlowCcConfig extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(required = true, length = 100)
    private String flowCode;

    @Field(length = 100, description = "Node id (null for flow-level CC)")
    private String nodeId;

    @Field(label = "CC Timing", description = "CC timing: OnSubmit, OnApprove, OnReject, OnComplete")
    private CcTiming ccTiming;

    @Field(label = "CC Name", length = 100, description = "Human-readable CC rule name")
    private String ccName;

    @Field(length = 30, description = "Recipient type: USER, ROLE, DEPT, INITIATOR, EXPRESSION")
    private String recipientType;

    @Field(description = "Recipient configuration (JSON): user IDs, role IDs, expression, etc.")
    private String recipientConfig;

    @Field(label = "CC Condition", length = 1000, description = "Optional condition expression that must evaluate to true for CC to fire")
    private String ccCondition;

    @Field(description = "Whether to create CC read tasks for recipients")
    private Boolean createReadTask;

    @Field(description = "Whether to send notification to recipients")
    private Boolean sendNotification;

    @Field(length = 500, description = "Optional message template for the notification")
    private String messageTemplate;

    @Field(description = "Whether this CC rule is active")
    private Boolean active;
}
