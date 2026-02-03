package io.softa.framework.orm.jdbc.pipeline.factory;

import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.enums.AccessType;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.pipeline.processor.FieldProcessor;
import io.softa.framework.orm.jdbc.pipeline.processor.StringProcessor;
import io.softa.framework.orm.jdbc.pipeline.processor.XToOneGroupProcessor;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

/**
 * The processor factory of creating XToOneGroupProcessor, which is processing
 * OneToOne/ManyToOne field and the cascaded fields that depend on them as a group.
 * The cascaded field depends on a OneToOne/ManyToOne field, they are processed together for input and output data.
 */
public class XToOneGroupProcessorFactory implements FieldProcessorFactory {

    private FlexQuery flexQuery;

    // THe mapping of ManyToOne/OneToOne fieldName to XToOneGroupProcessor
    private final Map<String, XToOneGroupProcessor> relatedFieldMap = new HashMap<>();

    public XToOneGroupProcessorFactory() {}

    public XToOneGroupProcessorFactory(FlexQuery flexQuery) {
        this.flexQuery = flexQuery;
    }

    /**
     * Create a field processor according to the field metadata.
     *
     * @param metaField field metadata object
     * @param accessType access type
     */
    @Override
    public FieldProcessor createProcessor(MetaField metaField, AccessType accessType) {
        FieldType fieldType = metaField.getFieldType();
        if (AccessType.READ.equals(accessType)) {
            if (StringUtils.isNotBlank(metaField.getCascadedField()) && metaField.isDynamic()) {
                // READ scenario: calculate dynamic cascaded field
                return this.newCascadedGroupProcessor(metaField, accessType);
            } else if (FieldType.TO_ONE_TYPES.contains(fieldType)) {
                if (ConvertType.EXPAND_TYPES.contains(flexQuery.getConvertType())
                        && !metaField.getFieldName().equals(flexQuery.getKeepIdField())) {
                    // Create the XToOneGroupProcessor when the field group is not created by the cascaded field.
                    return this.newXToOneGroupProcessor(metaField, accessType);
                } else {
                    // When READ OneToMany field, don't enhance the ManyToOne field of the associated model.
                    return fieldValueTypeCast(metaField, accessType);
                }
            }
        } else if (StringUtils.isNotBlank(metaField.getCascadedField()) && !metaField.isDynamic()) {
            // CREATE/UPDATE scenario: calculate stored cascaded field
            return this.newCascadedGroupProcessor(metaField, accessType);
        } else if (AccessType.UPDATE.equals(accessType) && FieldType.TO_ONE_TYPES.contains(fieldType)) {
            return this.newXToOneGroupProcessor(metaField, accessType);
        }
        return null;
    }

    private FieldProcessor fieldValueTypeCast(MetaField metaField, AccessType accessType) {
        FieldType relatedFieldType = ModelManager.getModelField(metaField.getRelatedModel(), ModelConstant.ID).getFieldType();
        if (FieldType.STRING.equals(relatedFieldType)) {
            return new StringProcessor(metaField, accessType);
        } else {
            return null;
        }
    }

    /**
     * Create or update XToOneGroupProcessor, bind XToOne field, cascaded fields that depend on this field,
     * and the expand fields of the associated model based on the ManyToOne/OneToOne field.
     *
     * @param cascadedField cascaded field metadata object
     * @param accessType    access type
     * @return XToOneGroupProcessor
     */
    private XToOneGroupProcessor newCascadedGroupProcessor(MetaField cascadedField, AccessType accessType) {
        String xToOneFieldName = cascadedField.getDependentFields().getFirst();
        if (this.relatedFieldMap.containsKey(xToOneFieldName)) {
            // Add the cascaded field to the existing XToOneGroupProcessor
            XToOneGroupProcessor xToOneGroupProcessor = this.relatedFieldMap.get(xToOneFieldName);
            xToOneGroupProcessor.addCascadedField(cascadedField);
            return null;
        }
        // Create the XToOneGroupProcessor for the first time
        MetaField xToOneField = ModelManager.getModelField(cascadedField.getModelName(), xToOneFieldName);
        XToOneGroupProcessor xToOneGroupProcessor = new XToOneGroupProcessor(xToOneField, accessType, flexQuery);
        xToOneGroupProcessor.addCascadedField(cascadedField);
        this.relatedFieldMap.put(xToOneFieldName, xToOneGroupProcessor);
        return xToOneGroupProcessor;
    }

    /**
     * Create a new XToOneGroupProcessor.
     *
     * @param xToOneField XToOne field metadata object
     * @param accessType access type
     * @return XToOneGroupProcessor
     */
    private XToOneGroupProcessor newXToOneGroupProcessor(MetaField xToOneField, AccessType accessType) {
        if (this.relatedFieldMap.containsKey(xToOneField.getFieldName())) {
            return null;
        }
        XToOneGroupProcessor xToOneGroupProcessor = new XToOneGroupProcessor(xToOneField, accessType, flexQuery);
        this.relatedFieldMap.put(xToOneField.getFieldName(), xToOneGroupProcessor);
        return xToOneGroupProcessor;
    }
}
