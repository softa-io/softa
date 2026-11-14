package io.softa.starter.metadata.entity;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;

/**
 * SysOptionSet — metadata catalog row describing an OptionSet (enum domain).
 *
 * <p>Self-described via {@code @Model} + per-field {@code @Field}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "System Option Set",
        activeControl = true,
        businessKey = {"optionSetCode"},
        description = "Metadata catalog of option sets",
        defaultOrder = {"optionSetCode"}
)
public class SysOptionSet extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field
    private String appCode;

    @Field(required = true)
    private String label;

    @Field(required = true)
    private String optionSetCode;

    /** Single immediately-prior option-set code for a declared rename; excluded from checksum/diff. */
    @Field
    private String renamedFrom;

    @Field(length = 512)
    private String description;

    @Field
    private Boolean active;

    /**
     * One-to-many to {@link SysOptionItem} (joins on the surrogate FK {@code optionSetId}).
     * Has NO {@code sys_option_set} column — SysCatalog and the DDL builder both
     * exclude X-to-many — but is emitted as a {@code sys_field} row. Populated in
     * memory by {@code OptionManager} (by {@code optionSetCode}).
     */
    @Field(fieldType = FieldType.ONE_TO_MANY, relatedModel = SysOptionItem.class, relatedField = "optionSetId")
    private List<SysOptionItem> optionItems;
}
