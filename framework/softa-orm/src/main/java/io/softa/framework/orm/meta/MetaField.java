package io.softa.framework.orm.meta;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import lombok.*;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.MaskingType;
import io.softa.framework.orm.enums.WidgetType;

/**
 * MetaField object
 */
@Getter
@Setter(AccessLevel.PACKAGE)
@ToString
@EqualsAndHashCode
public class MetaField implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long appId;

    private String labelName;

    private String fieldName;

    private String columnName;

    private String modelName;

    private Long modelId;

    private String description;

    private FieldType fieldType;

    private String optionSetCode;

    private String relatedModel;

    private String relatedField;

    private String joinModel;

    private String joinLeft;

    private String joinRight;

    private String cascadedField;

    // Field level filters used by frontend
    private String filters;

    // Special values: `now` for Date and DateTime fields. Ignore a case.
    private String defaultValue;

    // Memory compute attribute: Instantiated object of the default value.
    private Object defaultValueObject;

    private Integer length;

    private Integer scale;

    private boolean required;

    private boolean readonly;

    private boolean hidden;

    private boolean translatable;

    private boolean nonCopyable;

    private boolean unsearchable;

    private boolean computed;

    private String expression;

    /**
     * Memory compute attribute: The fields in the expression or cascadedField.
     * ComputedField scenario: field1 + field2 + field3 -> [field1, field2, field3]
     * CascadedField scenario: field1.field2 -> [field1, field2]
     */
    @Setter(AccessLevel.NONE)
    private List<String> dependentFields;

    private boolean dynamic;

    private boolean encrypted;

    /**
     * Whether this field is auto-filled from a sequence on INSERT.
     * Populated at startup by SequenceFieldRegistryInitializer based on sys_sequence rows
     * whose code matches "{modelName}.{fieldName}". Read-only after init.
     * Not persisted to sys_field; runtime-only flag.
     */
    private boolean autoSequence;

    private MaskingType maskingType;

    private WidgetType widgetType;

    /**
     * Get translation by language code from translations.
     * If the translation is not found, return the original name.
     *
     * @return label name
     */
    public String getLabelName() {
        String languageCode = ContextHolder.getContext().getLanguage().getCode();
        MetaFieldTrans labelTrans = TranslationCache.getFieldTrans(languageCode, id);
        if (labelTrans == null) {
            return labelName;
        } else {
            String translation = labelTrans.getLabelName();
            return StringUtils.isNotBlank(translation) ? translation : labelName;
        }
    }

    public boolean isDynamicCascadedField() {
        return dynamic && StringUtils.isNotBlank(cascadedField);
    }

    protected void setDependentFields(List<String> dependentFields) {
        this.dependentFields = Collections.unmodifiableList(dependentFields);
    }

    /**
     * Factory method to create a dynamic virtual field for query processing.
     */
    public static MetaField createDynamicField(String modelName, String fieldName) {
        MetaField metaField = new MetaField();
        metaField.modelName = modelName;
        metaField.fieldName = fieldName;
        metaField.cascadedField = fieldName;
        metaField.dynamic = true;

        // For dynamic virtual fields, split the fieldName by `.` into 2 groups.
        // If there are more than 2 cascading levels,
        // the remaining levels continue to cascade as a whole in the nested query.
        String[] parts = fieldName.split("\\.", 2);
        metaField.dependentFields = List.of(parts);

        // Use the type of the last field as the actual value type.
        MetaField lastField = ModelManager.getLastFieldOfCascaded(modelName, fieldName);
        metaField.fieldType = lastField.getFieldType();
        return metaField;
    }

}