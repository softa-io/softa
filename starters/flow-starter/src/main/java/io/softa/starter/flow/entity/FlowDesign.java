package io.softa.starter.flow.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.enums.FlowScenario;

/**
 * Persistent working copy of a flow design (draft).
 *
 * <p>There is exactly <em>one</em> {@code FlowDesign} record per {@code flowCode}.
 * It represents the latest saved state of the design-time graph, independent of
 * whether the flow has been compiled and published. Published revisions are stored
 * separately in {@link FlowBundle}.</p>
 *
 * <p>Lifecycle (editor surface on {@code /flow/designs}):
 * <ol>
 *   <li>Created via {@code POST /flow/designs}.</li>
 *   <li>Updated on every auto-save via {@code POST /flow/designs/{id}/save} —
 *       {@code versionLock} makes concurrent editor sessions fail with a version
 *       conflict instead of silently overwriting each other.</li>
 *   <li>{@link #publishedRevision} and {@link #publishedChecksum} are set each time
 *       {@code POST /flow/designs/{id}/publish} succeeds, pointing to the
 *       corresponding {@link FlowBundle#getRevision()}.</li>
 * </ol>
 * </p>
 */
@Data
@Model(
    multiTenant = true,
    idStrategy = IdStrategy.DISTRIBUTED_LONG,
    // concurrent-edit protection: stale saves are rejected with a version conflict
    versionLock = true,
    // copy is disabled — a duplicated row would collide on uk_tenant_flow_code;
    // duplication with a fresh flowCode is a dedicated editor operation instead
    copyable = false,
    displayName = {"flowName"},
    searchName = {"flowName", "flowCode"}
)
@Index(indexName = "uk_tenant_flow_code", fields = {"tenantId", "flowCode"}, unique = true,
       message = "A flow with this code already exists.")
@Index(indexName = "idx_tenant_scenario", fields = {"tenantId", "scenario"})
@EqualsAndHashCode(callSuper = true)
public class FlowDesign extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID", required = true)
    private Long tenantId;

    @Field(required = true, length = 100, description = "Flow display name (denormalised for list views)")
    private String flowName;

    @Field(required = true, length = 100, description = "Flow code (business identifier)")
    private String flowCode;

    @Field(description = "Flow scenario (denormalised for list views)")
    private FlowScenario scenario;

    @Field(description = "Optimistic-lock version; the editor echoes the loaded value on save "
            + "and a mismatch is rejected as a version conflict")
    private Integer version;

    @Field(description = "Full design definition (stored as JSON, auto-converted by ORM)")
    private DesignFlowDefinition designJson;

    @Field(copyable = false,
            description = "Revision of the most recent successful publish (null = never published)")
    private Integer publishedRevision;

    @Field(copyable = false,
            description = "SHA-256 of designJson at the most recent successful publish; "
                    + "compared against the current draft to derive the editor's dirty flag")
    private String publishedChecksum;
}
