package io.softa.starter.metadata.controller.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.MaskingType;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.framework.orm.enums.WidgetType;
import io.softa.framework.orm.meta.FieldConstraints;

/**
 * MetaFieldDTO
 */
@Data
@Schema(name = "MetaFieldDTO")
public class MetaFieldDTO {
    private String label;
    private String fieldName;
    private String modelName;
    private FieldType fieldType;
    private String description;

    private Boolean required;
    private Integer length;
    private Integer scale;
    private Object defaultValue;
    private Boolean readonly;
    private Boolean hidden;
    private Boolean translatable;
    private Boolean copyable;
    private Boolean unsearchable;
    private Boolean computed;
    private Boolean dynamic;
    private Boolean encrypted;

    private String optionSetCode;
    private String relatedModel;
    private String relatedField;
    private String joinModel;
    private String joinLeft;
    private String joinRight;
    private String cascadedField;

    private String filters;
    private MaskingType maskingType;
    private WidgetType widgetType;
    private OnDelete onDelete;

    /**
     * Countries this field applies to (ISO 3166-1 alpha-2); empty or null = every country.
     *
     * <p>Rides along with the metadata the client already fetches, which is the point of keeping the
     * declaration on the field rather than in a table of its own: the screens that need it — the
     * employee form, the import column picker — are asking for this model's fields anyway.
     */
    private List<String> countries;

    /**
     * Value domain and conditional rules, exactly as stored — the frontend evaluates the same object
     * against the form (zod bounds and pattern, star / visibility / readonly from the conditions,
     * date-picker bounds derived from {@code invalidWhen}). Null when the field declares none.
     */
    private FieldConstraints constraints;
}
