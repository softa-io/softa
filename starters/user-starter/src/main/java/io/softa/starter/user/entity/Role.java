package io.softa.starter.user.entity;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * Role — per-tenant business role with optional dynamic membership rule.
 *
 * <p>{@code code} is the stable machine identifier (NULL for admin-created
 * roles; non-null for system-reserved roles). Reserved code:
 * <ul>
 *   <li>{@code SUPER_ADMIN} — short-circuit bypass at permission / data-scope
 *       evaluation. Cannot be deleted / renamed / made inactive.
 *       Revoking the last Manual holder is rejected at the service layer.</li>
 * </ul>
 *
 * <p>(tenant_id, name) UNIQUE serves as the human business identifier.
 *
 * <p><b>Metadata note:</b> {@code io.softa.starter.user.entity} is NOT in scanner-scope, so these
 * annotations are not reconciled at runtime — the authoritative metadata is the studio-managed
 * {@code sys_*}. Annotations mirror the live {@code sys_field} for documentation / future scanning.
 * The reverse relations ({@code users} M2M, {@code roleNavigations}/{@code roleDataScopes}/
 * {@code roleSensitiveFieldSets} O2M) are declared below to mirror live.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true, displayName = {"name"}, searchName = {"name"}, defaultOrder = {"name"})
@Index(indexName = "uk_role_tenant_name", fields = {"tenantId", "name"}, unique = true,
        message = "A role with this name already exists.")
// code is nullable (admin-created roles); the unique index treats NULLs as distinct, so it only
// enforces uniqueness of the reserved machine codes (e.g. SUPER_ADMIN) per tenant.
@Index(indexName = "uk_role_tenant_code", fields = {"tenantId", "code"}, unique = true,
        message = "A role with this code already exists.")
public class Role extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(length = 32, description = "Role name (also business identifier; UNIQUE per tenant)")
    private String name;

    @Field(length = 256, description = "Optional description")
    private String description;

    @Field(length = 32, description = "Stable machine code (e.g. SUPER_ADMIN). NULL for admin-created roles.")
    private String code;

    @Field(description = "Active flag; inactive roles are skipped during PermissionInfo enrich")
    private Boolean active;

    @Field(description = "Dynamic membership filter (JSON FilterCondition). DynamicRoleSyncJob auto-assigns matching users (source=DYNAMIC)")
    private JsonNode dynamicFilter;

    /**
     * The departments this role reaches on every model that belongs to a department — an outer bound,
     * AND-ed per role onto its rules like the company grant. A JSON array of rules in the data-scope
     * form: {@code {"scopeType":"ALL"}}, or any of
     * {@code {"scopeType":"DEPT_SUBTREE","scopeExpr":{"deptId":"<id>"}}} (that department and everything
     * under it) and the conditions worked out for the holder: {@code OWN_DEPARTMENT},
     * {@code MANAGED_DEPARTMENTS} and {@code DIRECT_REPORTS}. A record has to meet any one of them.
     * {@code null} = not configured, unrestricted; {@code []} = no department.
     */
    @Field(description = "Department grant (JSON rules: ALL, or DEPT_SUBTREE with a fixed deptId, OWN_DEPARTMENT, MANAGED_DEPARTMENTS, DIRECT_REPORTS). NULL = unrestricted, [] = none")
    private JsonNode departmentGrant;

    // Reverse relations (mirror live sys_field). Dynamic (not stored) — loaded only when requested.
    @Field(fieldType = FieldType.MANY_TO_MANY, relatedModel = UserAccount.class,
            joinModel = UserRoleRel.class, joinLeft = "roleId", joinRight = "userId",
            description = "Users assigned this role (M2M via UserRoleRel)")
    private List<Long> users;

    @Field(fieldType = FieldType.ONE_TO_MANY, relatedModel = RoleNavigation.class, relatedField = "roleId",
            description = "This role's navigation grants")
    private List<RoleNavigation> roleNavigations;

    @Field(fieldType = FieldType.ONE_TO_MANY, relatedModel = RoleDataScope.class, relatedField = "roleId",
            description = "This role's per-model data scopes")
    private List<RoleDataScope> roleDataScopes;

    @Field(fieldType = FieldType.ONE_TO_MANY, relatedModel = RoleSensitiveFieldSet.class, relatedField = "roleId",
            description = "This role's sensitive-field-set grants")
    private List<RoleSensitiveFieldSet> roleSensitiveFieldSets;
}
