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
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;

/**
 * Append-only execution trace entry for a flow runtime instance.
 *
 * <p>Split out from {@code flow_instance.trace} (LONGTEXT JSON)
 * so that per-step persistence cost is bounded by the size of the change
 * rather than by the cumulative trace length.</p>
 */
@Data
@Model(
    multiTenant = true,
    idStrategy = IdStrategy.DISTRIBUTED_LONG,
    copyable = false
)
@Index(fields = {"instanceId", "sequence"}, unique = true)
@Index(fields = {"tenantId", "instanceId"})
@EqualsAndHashCode(callSuper = true)
public class FlowExecutionTrace extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Runtime instance id", required = true)
    private String instanceId;

    @Field(label = "Monotonic position within the instance trace", required = true)
    private Integer sequence;

    @Field(label = "Flow code at the time of the event", length = 100)
    private String flowCode;

    @Field(label = "Node id when the event is node-scoped", length = 100)
    private String nodeId;

    @Field(label = "Node type when applicable")
    private FlowNodeType flowNodeType;

    @Field(label = "Trace event type", required = true)
    private FlowTraceEventType eventType;

    @Field(label = "Event timestamp")
    private LocalDateTime eventTime;

    @Field(length = 2000, label = "Free-form message")
    private String message;
}
