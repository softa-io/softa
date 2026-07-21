package io.softa.starter.metadata.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.MaskingType;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.framework.orm.enums.WidgetType;

/**
 * SysField — metadata catalog row describing a Softa Field.
 *
 * <p>Self-described via {@code @Model} + per-field {@code @Field}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "System Field",
        businessKey = {"modelName", "fieldName"},
        description = "Metadata catalog of fields"
)
public class SysField extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field
    private String appCode;

    @Field
    private String label;

    @Field(required = true)
    private String fieldName;

    /** Single immediately-prior field name for a declared rename; excluded from checksum/diff. */
    @Field
    private String renamedFrom;

    @Field
    private String columnName;

    // The owning model's business name — a plain attribute (half of businessKey) and the column the
    // post-scan populator joins on to resolve modelId. The metamodel links fields to models by this
    // name in ModelManager, independent of the surrogate FK below.
    @Field(required = true)
    private String modelName;

    // Surrogate FK to the owning model. relatedField defaults to id (BIGINT). Nullable and
    // EXCLUDED from the scanner diff (SysCatalog.EXCLUDED): the parser cannot set it (the parent's
    // surrogate id is DB-assigned), so it is resolved post-scan from modelName — see SysReferenceSql.
    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = SysModel.class)
    private Long modelId;

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

    // Initialized to true (the column is NOT NULL DEFAULT 1) so hand-constructed
    // instances — scanner paths go through AnnotationParser — never insert NULL.
    @Field(defaultValue = "true")
    private Boolean copyable = Boolean.TRUE;

    @Field
    private Boolean unsearchable;

    @Field(label = "Is Computed")
    private Boolean computed;

    // length > 16383 renders as TEXT on MySQL (utf8mb4 VARCHAR cap), matching
    // the legacy TEXT(20000) column; PostgreSQL renders VARCHAR(20000).
    @Field(length = 20000)
    private String expression;

    @Field(label = "Dynamic Field")
    private Boolean dynamic;

    @Field(label = "Is Encrypted")
    private Boolean encrypted;

    // Auto-fill from a sequence on INSERT when blank; see SequenceProcessor. Declared
    // via @Field(autoSequence = true) on the entity and reconciled by the scanner.
    @Field(label = "Auto Sequence")
    private Boolean autoSequence;

    @Field
    private MaskingType maskingType;

    @Field
    private WidgetType widgetType;

    // System-computed at reconciliation time (never declared on @Field). For a TO_ONE FK
    // this holds the resolved PHYSICAL type of the referenced column (STRING / LONG / ...)
    // while fieldType stays the logical MANY_TO_ONE / ONE_TO_ONE; the resolved width lives
    // in length / scale. Null for every non-FK field. See ReferenceColumnResolver.
    @Field
    private FieldType relatedFieldType;
}
