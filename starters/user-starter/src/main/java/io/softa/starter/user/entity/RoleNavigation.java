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
import io.softa.framework.orm.enums.OnDelete;

/**
 * RoleNavigation — one row = one navigation's complete grant for a role.
 *
 * The navigation_id may reference any non-GROUP node (MENU / BUTTON / TAB);
 * Validator ⑩ rejects GROUP and pure-container MENU (nav.model = null).
 *
 * <p><b>Metadata note:</b> {@code io.softa.starter.user.entity} is NOT in scanner-scope; annotations
 * mirror the studio-managed live {@code sys_field} (not reconciled at runtime). The former inline
 * {@code dataScopes} / {@code sensitiveFieldSetIds} fields have been removed — a role's data scopes and
 * sensitive-field grants are now normalised into {@link RoleDataScope} / {@link RoleSensitiveFieldSet}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true)
@Index(indexName = "uk_role_navigation_tenant_role_nav", fields = {"tenantId", "roleId", "navigationId"},
        unique = true, message = "This role already has a grant for this navigation.")
public class RoleNavigation extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Role", fieldType = FieldType.MANY_TO_ONE, relatedModel = Role.class,
            onDelete = OnDelete.CASCADE, description = "Role ID (FK role.id)")
    private Long roleId;

    @Field(label = "Navigation", length = 128,
            description = "Navigation ID (FK navigation.id). Must reference MENU / BUTTON / TAB only — not GROUP or pure-container MENU")
    private String navigationId;

    @Field(description = "Granted permission IDs under this nav (string array; default = all, subset = admin-trimmed)")
    private JsonNode permissionIds;
}
