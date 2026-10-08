package io.softa.starter.user.entity;

import java.io.Serial;
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
 * RoleDataScope — one row = a role's row-level data scope for ONE model.
 *
 * <p>Replaces the per-nav {@code role_navigation.data_scopes} column: data
 * scope is now attached to the (role, model) pair, matching how
 * {@code PermissionInfoEnricher} keys {@code modelScopeMap} at runtime.
 * Multiple scope rules for the same model are OR-combined and stored inline
 * in {@link #dataScopes}. {@code (tenant_id, role_id, model)} is unique — the
 * OR-union across the wizard's selected navigations is materialised here at
 * save time instead of being re-aggregated on every enrich.
 *
 * <p>Only "directly queryable" models get a row (the primary model of a
 * granted navigation). Child/related models that merely contribute sensitive
 * fields (e.g. {@code EmpBankAccount} under {@code Employee}) do NOT need a
 * scope row — their field masking is driven by {@link RoleSensitiveFieldSet}.
 *
 * <p><b>Metadata note:</b> {@code io.softa.starter.user.entity} is NOT in scanner-scope; annotations
 * mirror the studio-managed live {@code sys_field} (not reconciled at runtime).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true)
@Index(indexName = "uk_role_data_scope_tenant_role_model", fields = {"tenantId", "roleId", "model"},
        unique = true, message = "This role already has a data scope for this model.")
public class RoleDataScope extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Role", fieldType = FieldType.MANY_TO_ONE, relatedModel = Role.class,
            description = "Role ID (FK role.id)")
    private Long roleId;

    @Field(length = 64,
            description = "Queried model name (PascalCase), e.g. Employee / Department. The model whose rows this scope filters.")
    private String model;

    @Field(description = "Scope rules (OR-combined). Array of {scopeType, scopeExpr?}")
    private JsonNode dataScopes;

    /**
     * A condition on the row's own attributes, AND-ed onto {@link #dataScopes} — "my department, and
     * only the contractors". The rules say whose rows; this narrows them by what the rows
     * are. Null for every scope written before it existed, which keeps their reach unchanged.
     */
    @Field(description = "Optional condition (Filters JSON) AND-ed onto the scope rules; may name cascaded fields")
    private JsonNode scopeCondition;
}
