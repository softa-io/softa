package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.MaskingType;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.framework.orm.enums.WidgetType;

/**
 * DesignField Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        // hard delete (no softDelete) — a deleted design row is simply absent from the env's
        // desired state (converge-to-HEAD; recovery is via env↔env merge), and physical delete lets the
        // per-env UNIQUE(env_id, …) index work (a retained soft-deleted row would block recreate). See DesignModel.
        copyable = false,   // copy disabled (would clone the per-env business key) — see DesignModel.
        // envId scopes the businessKey (see DesignModel).
        businessKey = {"envId", "modelName", "fieldName"},
        displayName = {"label"}
)
public class DesignField extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "App ID")
    private Long appId;

    @Field(label = "Model ID")
    private Long modelId;

    // Per-env design: see DesignModel — envId scopes the row (NOT NULL, V19). The per-env
    // business key (env_id + modelName + fieldName) is the identity (no logicalId).
    @Field(label = "Env ID")
    private Long envId;

    @Field(required = true)
    private String label;

    @Field(required = true)
    private String fieldName;

    /** Single immediately-prior field name for a declared rename; excluded from checksum/diff. */
    @Field
    private String renamedFrom;

    @Field
    private String columnName;

    @Field(required = true)
    private String modelName;

    @Field(length = 512)
    private String description;

    @Field(required = true)
    private FieldType fieldType;

    @Field
    private String optionSetCode;

    @Field
    private String relatedModel;

    @Field
    private String relatedField;

    // FK delete strategy for a TO_ONE relation; null = KEEP (default).
    @Field
    private OnDelete onDelete;

    @Field
    private String joinModel;

    @Field(label = "Join Model Left Key")
    private String joinLeft;

    @Field(label = "Join Model Right Key")
    private String joinRight;

    @Field(length = 256)
    private String cascadedField;

    @Field(length = 256)
    private String filters;

    @Field(length = 256)
    private String defaultValue;

    @Field
    private Integer length;

    @Field
    private Integer scale;

    @Field(label = "Is Required")
    private Boolean required;

    @Field(label = "Is Readonly")
    private Boolean readonly;

    @Field
    private Boolean hidden;

    @Field
    private Boolean translatable;

    // Structural mirror of sys_field.copyable governance; the cross-lane checksum requires
    // design_* and sys_* to match field-for-field. Initialized true (column is NOT NULL DEFAULT 1).
    @Field(defaultValue = "true")
    private Boolean copyable = Boolean.TRUE;

    @Field
    private Boolean unsearchable;

    @Field(label = "Is Computed")
    private Boolean computed;

    @Field(length = 20000)
    private String expression;

    @Field(label = "Dynamic Field")
    private Boolean dynamic;

    @Field(label = "Is Encrypted")
    private Boolean encrypted;

    // Structural mirror of sys_field.auto_sequence (auto-fill from a sequence on INSERT when blank); 
    // the cross-lane checksum requires design_* and sys_* to match field-for-field.
    @Field(label = "Auto Sequence")
    private Boolean autoSequence;

    @Field
    private MaskingType maskingType;

    @Field
    private WidgetType widgetType;

    // Design-time provenance only: which DesignFieldDomain this field was applied from
    // (a one-time template fill — applyDomain copies the domain's attrs into the columns above). NOT a
    // live binding (no propagation) and NOT a sys_field column, so it is never checksummed nor shipped to
    // runtime — the converge engine is domain-agnostic.
    @Field
    private Long domainId;

    // System-computed (never user-authored): for a TO_ONE FK, the resolved PHYSICAL type of
    // the referenced column (STRING / LONG / ...), mirrored so DDL renders VARCHAR(n) / BIGINT.
    // fieldType stays the logical MANY_TO_ONE / ONE_TO_ONE; resolved width lives in length/scale.
    // Recomputed on save (own type); null for non-FK fields.
    @Field
    private FieldType relatedFieldType;
}
