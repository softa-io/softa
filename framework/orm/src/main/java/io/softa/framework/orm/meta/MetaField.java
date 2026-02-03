package io.softa.framework.orm.meta;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.MaskingType;
import io.softa.framework.orm.enums.WidgetType;

/**
 * MetaField object
 */
@Data
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

    private String jointModel;

    private String jointLeft;

    private String jointRight;

    private String cascadedField;

    // Field level filters used by frontend
    private String filters;

    // Special values: `now` for Date and DateTime fields. Ignore case.
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
    private List<String> dependentFields;

    private boolean dynamic;

    private boolean encrypted;

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
}