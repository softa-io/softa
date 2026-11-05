package io.softa.starter.metadata.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.enums.OptionItemIcon;
import io.softa.framework.base.enums.OptionItemTone;
import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;

/**
 * SysOptionItem — metadata catalog row describing an OptionSet member.
 *
 * <p>Self-described via {@code @Model} + per-field {@code @Field}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "System Option Item",
        activeControl = true,
        displayName = {"label"},
        businessKey = {"optionSetCode", "itemCode"},
        description = "Metadata catalog of option items",
        defaultOrder = {"optionSetCode", "sequence"}
)
// The business key had no index behind it. An option set whose item codes repeat cannot do its job —
// a stored code stops naming one item — and the reconciler, which matches rows by that key, has no
// way to tell which of two it meant to update. Two retired sets had drifted into exactly that state.
@Index(indexName = "uk_sys_option_item_code", fields = {"optionSetCode", "itemCode"},
        unique = true, message = "This item code already exists in the option set.")
// Names have to be unique too, because a name is what people type. Import columns are moving to
// accept the displayed name rather than the code, and a name matching two items leaves the importer
// nothing to choose between — both rows are valid answers to what it was asked.
//
// Scoped to the set alone, not to (app, set). An option set is addressed globally by its code:
// SysOptionSet declares businessKey = {optionSetCode}, and OptionManager resolves items through
// (optionSetCode, itemCode) and (optionSetCode, label), taking no appCode at all. Two apps defining
// the same set code is therefore already unresolvable at runtime — keying the index by app would
// permit that state rather than prevent it.
//
// Platform sets are flat, so whole-set uniqueness is the right scope here. Tenant-authored sets are
// NOT: `ResignationReason` deliberately carries four items labelled "Others", one under each
// `ResignationType`, and only one is ever visible at a time. Their uniqueness is per parent, which
// needs a different index — see the wiki design note, not this file.
@Index(indexName = "uk_sys_option_item_label", fields = {"optionSetCode", "label"},
        unique = true, message = "This item name already exists in the option set.")
public class SysOptionItem extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field
    private String appCode;

    // The owning option set's business code — a plain attribute (half of businessKey) and the column
    // the post-scan populator joins on to resolve optionSetId.
    @Field(required = true)
    private String optionSetCode;

    // Surrogate FK to the owning option set. relatedField defaults to id (BIGINT). Nullable
    // and EXCLUDED from the scanner diff: resolved post-scan from optionSetCode — see SysReferenceSql.
    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = SysOptionSet.class)
    private Long optionSetId;

    @Field
    private Integer sequence;

    @Field(required = true)
    private String itemCode;

    /** Single immediately-prior item code for a declared rename; excluded from checksum/diff. */
    @Field
    private String renamedFrom;

    @Field(required = true)
    private String label;

    @Field
    private String parentItemCode;

    @Field
    private OptionItemTone itemTone;

    @Field
    private OptionItemIcon itemIcon;

    @Field(length = 512)
    private String description;

    @Field
    private Boolean active;
}
