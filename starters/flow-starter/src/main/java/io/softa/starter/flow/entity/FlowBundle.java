package io.softa.starter.flow.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.enums.FlowScenario;

/**
 * Persistent entity for compiled flow definition bundles.
 * The entire {@code CompiledFlowDefinition} graph is serialized as a JSON
 * string in the {@code compiledJson} column so that a single row fully
 * describes one published revision of a flow.
 */
@Data
@Model(
    multiTenant = true,
    idStrategy = IdStrategy.DISTRIBUTED_LONG,
    versionLock = true,
    copyable = false
)
@Index(indexName = "uk_tenant_design_revision", fields = {"tenantId", "designId", "revision"}, unique = true)
@Index(indexName = "idx_tenant_flow_revision", fields = {"tenantId", "flowCode", "revision", "active"})
@EqualsAndHashCode(callSuper = true)
public class FlowBundle extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID", required = true)
    private Long tenantId;

    @Field(description = "Optimistic-lock version; serializes concurrent publish/activate so a design "
            + "cannot end up with two active revisions", required = true, defaultValue = "1")
    private Integer version;

    @Field(required = true, length = 100, description = "Flow code (business identifier)")
    private String flowCode;

    @Field(length = 100)
    private String flowName;

    @Field(required = true, description = "Published revision number")
    private Integer revision;

    @Field(description = "Execution scenario")
    private FlowScenario scenario;

    @Field(description = "Whether the flow executes synchronously")
    private Boolean sync;

    @Field(description = "Whether to roll back on failure")
    private Boolean rollbackOnFail;

    @Field(label = "Design ID", description = "FK to FlowDesign.id; null for bundles published before this field was added")
    private Long designId;

    @Field(length = 100000, description = "Compiled flow definition (JSON)")
    private String compiledJson;

    @Field(fieldType = FieldType.DTO, description = "Design flow definition at publish time (auto-converted by ORM)")
    private DesignFlowDefinition designJson;

    @Field(description = "Compile timestamp")
    private LocalDateTime compiledAt;

    @Field(description = "Published timestamp")
    private LocalDateTime publishedAt;

    @Field(length = 500, description = "Change description for this revision")
    private String changeDescription;

    @Field(description = "Whether this is the currently effective revision (one per design)")
    private Boolean active;

    @Field(description = "Debug-run bundle: compiled from an unpublished draft, never active, "
            + "hidden from revision lists, purged by the maintenance job")
    private Boolean debug;
}
