package io.softa.starter.tenant.entity;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * Tenant-authored option set — a dropdown domain owned by one tenant.
 * <p>
 * The tenant-scoped counterpart of the platform {@code SysOptionSet} catalog: the platform lane is
 * authored in Java annotations / Studio and shared by every tenant, whereas rows here are created by
 * a tenant at runtime and isolated to it (the ORM stamps and filters {@code tenantId}). Platform
 * option sets are NOT overridden or extended by these rows — per-tenant customization of platform
 * metadata is out of scope; this is tenant-owned business data that happens to have the same shape.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "Tenant Option Set",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        multiTenant = true,
        activeControl = true,
        businessKey = {"optionSetCode"},
        displayName = {"label"},
        searchName = {"optionSetCode", "label"},
        description = "Tenant-authored option sets (per-tenant dropdown domains)",
        defaultOrder = {"optionSetCode"}
)
@Index(indexName = "uk_tenant_option_set", fields = {"tenantId", "optionSetCode"}, unique = true,
        message = "This option set code is already used.")
public class TenantOptionSet extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID",
            description = "Owning tenant. Auto-stamped and isolated by the ORM on every write / read.")
    private Long tenantId;

    @Field(required = true)
    private String label;

    @Field(required = true, description = "Business code, unique within the tenant")
    private String optionSetCode;

    @Field(length = 512)
    private String description;

    @Field
    private Boolean active;

    /**
     * One-to-many to {@link TenantOptionItem}, joined on the surrogate FK {@code optionSetId}.
     * A virtual field: no column on {@code tenant_option_set}, emitted as a {@code sys_field} row.
     */
    @Field(fieldType = FieldType.ONE_TO_MANY, relatedModel = TenantOptionItem.class,
            relatedField = "optionSetId")
    private List<TenantOptionItem> optionItems;
}
