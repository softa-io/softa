package io.softa.starter.permission.entity;

import java.io.Serial;
import java.util.List;

import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.permission.scope.IdentityScopeCompiler;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;

/**
 * Data-scope type registry — the single source of truth for "which data-scope
 * types exist and to which models each applies". Platform-level seed
 * ({@code data-system/DataScopeType.Builtin.json}), read-only reference data
 * shared by all tenants. The same rows drive BOTH sides:
 *
 * <ul>
 *   <li><b>enforce</b> — {@link ScopeApplicabilityResolver} →
 *       {@code ScopeRuleCompiler} fail-fast on inapplicable rules;</li>
 *   <li><b>authoring</b> — the role wizard's "which scope checkboxes to enable",
 *       which {@code user-starter} derives by reading THIS model by name (约定读),
 *       without importing any permission type.</li>
 * </ul>
 *
 * <p>Code-as-id: {@link #id} IS the scope-type code (e.g. {@code "SELF"},
 * {@code "DEPT_SUBTREE"}), matching {@code ScopeType.name()} so the enforce enum
 * and this data agree by convention.
 *
 * <h3>Applicability + compile, both expressed as one filter template (2026-07-17)</h3>
 * <b>Identity types</b> (SELF / DIRECT_REPORTS / CREATED_BY_SELF)
 * carry a {@link #filter} template — a Filters-shape JSON whose leaf values are env
 * placeholders (e.g. {@code ["employeeId","=","USER_EMP_ID"]}). That single template
 * drives BOTH sides:
 * <ul>
 *   <li><b>applicability</b> — a model is eligible iff it carries the field(s) the
 *       template references ({@link ScopeApplicabilityResolver} derives this by
 *       parsing the template, so {@link #applicableFields} is no longer set on these
 *       rows);</li>
 *   <li><b>compile</b> — {@link IdentityScopeCompiler} emits the template as-is and
 *       lets {@code FilterUnitParser} resolve the env placeholder at SQL time (the
 *       same path CUSTOM scopes use).</li>
 * </ul>
 * When the queried model IS the {@link #identityModel} (the identity entity's own
 * table), the {@link #identityFilter} template is used instead (model-swap, e.g. SELF
 * filters {@code id} on {@code Employee} but {@code employeeId} elsewhere).
 *
 * <p><b>Code-contributor types</b> (DEPT_SUBTREE / MANAGED_DEPARTMENTS) need a runtime
 * DB lookup, so they have no filter template; their eligibility still comes from
 * {@link #applicableFields}. {@code ALL} / {@code CUSTOM} set {@link #appliesToAll}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(idStrategy = IdStrategy.EXTERNAL_ID, businessKey = {"id"},
        description = "Data-scope type registry (applicability metadata)")
public class DataScopeType extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID", length = 64,
            description = "Scope-type code = ScopeType.name() (SELF / DEPT_SUBTREE / CUSTOM / ...)")
    private String id;

    @Field(required = true, length = 100, description = "Display name for the wizard")
    private String name;

    @Field(length = 255, description = "Human-readable description")
    private String description;

    @Field(description = "Wizard display order")
    private Integer sortOrder;

    @Field(description = "Applies to every model (ALL / CUSTOM)")
    private Boolean appliesToAll;

    @Field(description = "Code-contributor types (DEPT_SUBTREE / MANAGED_DEPARTMENTS): eligible on models "
            + "with ANY of these fields; identity types use filter instead")
    private List<String> applicableFields;

    @Field(description = "Identity type: filter template with env-placeholder values, "
            + "e.g. [\"employeeId\",\"=\",\"USER_EMP_ID\"]")
    private JsonNode filter;

    @Field(length = 100, description = "Model-swap: on this model use identityFilter instead of filter (e.g. Employee)")
    private String identityModel;

    @Field(description = "Model-swap: filter used when the queried model is identityModel "
            + "(e.g. SELF's [\"id\",\"=\",\"USER_EMP_ID\"])")
    private JsonNode identityFilter;
}
