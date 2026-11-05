package io.softa.starter.user.entity;

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
import io.softa.starter.user.enums.NavigationType;

/**
 * Navigation node — sidebar / route entry registered by dev's manifest.
 * Forms a tree via parentId; type ∈ GROUP / MENU / BUTTON / TAB.
 * Loaded from data-system/navigation.json at startup.
 *
 * <p><b>Metadata note:</b> this package ({@code io.softa.starter.user.entity}) is NOT in scanner-scope,
 * so these annotations are NOT reconciled at runtime — the authoritative metadata lives in {@code sys_*}
 * (studio-managed). Annotations mirror the live {@code sys_field} for documentation / future scanning.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(idStrategy = IdStrategy.EXTERNAL_ID, displayName = {"name"}, searchName = {"name"}, defaultOrder = {"parentId", "sortOrder"})
@Index(indexName = "idx_navigation_parent", fields = {"parentId"})
public class Navigation extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID", length = 128, description = "Navigation ID, business-named (e.g. core-hr.employee.employee)")
    private String id;

    @Field(label = "Parent ID", fieldType = FieldType.MANY_TO_ONE, relatedModel = Navigation.class,
            description = "Parent navigation ID; null for top-level nodes")
    private String parentId;

    @Field(description = "Display name (e.g. 'Employees')")
    private String name;

    @Field(length = 128,
            description = "Frontend route (Next.js dynamic segments allowed). Null for GROUP / TAB / pure-container MENU")
    private String route;

    @Field(description = "Node type: GROUP / MENU / BUTTON / TAB")
    private NavigationType type;

    @Field(length = 32,
            description = "Primary model code (PascalCase, matches sys_model.model_name). Null for GROUP and pure-container MENU")
    private String model;

    @Field(description = "Sort order within siblings")
    private Integer sortOrder;

    @Field(fieldType = FieldType.ONE_TO_MANY, relatedModel = Permission.class, relatedField = "navigationId",
            description = "Permissions registered under this navigation")
    private List<Permission> permissions;
}
