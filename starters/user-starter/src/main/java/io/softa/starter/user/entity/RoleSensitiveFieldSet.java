package io.softa.starter.user.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.OnDelete;

/**
 * RoleSensitiveFieldSet — one row = one sensitive_field_set grant for a role
 * (normalised role ↔ SFS many-to-many).
 *
 * <p>Replaces the per-nav {@code role_navigation.sensitive_field_set_ids}
 * JSON array. Deliberately has NO {@code model} column: each SFS already
 * carries its own canonical model (via {@code SensitiveFieldSetCache.modelOf}),
 * so a grant naturally routes into {@code modelSensitiveFieldSetsMap} keyed by
 * the SFS's own model — including child/sub-object models such as
 * {@code EmpBankAccount} whose fields surface through {@code Employee}.
 *
 * <p>Storing one row per grant (instead of a JSON array) buys per-grant audit
 * (who granted which SFS, when), a real {@code (tenant_id, role_id,
 * sensitive_field_set_id)} uniqueness guarantee, and cheap "which roles hold
 * SFS X" reverse queries.
 *
 * <p><b>Metadata note:</b> {@code io.softa.starter.user.entity} is NOT in scanner-scope; annotations
 * mirror the studio-managed live {@code sys_field} (not reconciled at runtime).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true)
@Index(indexName = "uk_role_sensitive_field_set_tenant_role_sfs",
        fields = {"tenantId", "roleId", "sensitiveFieldSetId"}, unique = true,
        message = "This role already holds this sensitive field set.")
public class RoleSensitiveFieldSet extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Role", fieldType = FieldType.MANY_TO_ONE, relatedModel = Role.class,
            onDelete = OnDelete.CASCADE, description = "Role ID (FK role.id)")
    private Long roleId;

    @Field(length = 64,
            description = "Granted sensitive_field_set id (FK sensitive_field_set.id). Single value; the SFS carries its own canonical model.")
    private String sensitiveFieldSetId;
}
