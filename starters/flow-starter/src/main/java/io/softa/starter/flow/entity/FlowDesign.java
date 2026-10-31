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
 * <p>There is exactly <em>one</em> {@code FlowDesign} record per flow, and its {@code id} is the flow's
 * handle everywhere else (bundles, instances, tasks, delegations). Its {@code flowName} is unique
 * within the tenant, so a flow is also known to people by its name.
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
 *       corresponding {@code FlowBundle#getRevision()}.</li>
 *   <li>{@link #enabled} switches the flow off and on ({@code POST /flow/designs/{id}/disable} /
 *       {@code enable}) without touching which revision is effective. Once published, the flow is never
 *       deleted — disabled instead — since its revisions, instances and approval history refer to it.</li>
 * </ol>
 * </p>
 */
@Data
@Model(
    multiTenant = true,
    // a string id travels unchanged between the frontend and the backend
    idStrategy = IdStrategy.DISTRIBUTED_STRING,
    // concurrent-edit protection: stale saves are rejected with a version conflict
    versionLock = true,
    // copy is disabled — a duplicated row would collide on uk_tenant_flow_name;
    // duplication under a fresh name is a dedicated editor operation instead
    copyable = false,
    displayName = {"flowName"},
    searchName = {"flowName"}
)
@Index(indexName = "uk_tenant_flow_name", fields = {"tenantId", "flowName"}, unique = true,
       message = "A flow with this name already exists.")
@Index(indexName = "idx_tenant_scenario", fields = {"tenantId", "scenario"})
@Index(fields = {"tenantId", "modelName"})
@EqualsAndHashCode(callSuper = true)
public class FlowDesign extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private String id;

    @Field(label = "Tenant ID", required = true)
    private Long tenantId;

    @Field(required = true, length = 100, description = "Flow name, unique within the tenant")
    private String flowName;

    @Field(description = "Flow scenario, fixed when the flow is created; the design document follows it",
           readonlyWhen = """
                   [["@mode", "=", "update"]]
                   """)
    private FlowScenario scenario;

    @Field(description = "The model whose records the flow starts on, as its trigger names it — an Approval flow's "
            + "document, the model a change trigger watches; empty when its trigger starts on no record (a schedule, "
            + "an API call, a parent flow). Kept from the trigger on every save and publish, for lookups. An Approval "
            + "flow's is chosen when it is created and fixed: its trigger is put back from it")
    private String modelName;

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

    @Field(required = true, defaultValue = "true",
            description = "Whether the flow runs: a disabled flow starts nothing — no submission, change, schedule, "
                    + "API call or parent flow reaches it. Switched off only once none of its instances is unfinished. "
                    + "Independent of which revision is effective, and of publishing")
    private Boolean enabled;
}
