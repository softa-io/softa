package io.softa.starter.metadata.service.impl;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.softa.framework.orm.meta.LookupKeys;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.metadata.controller.dto.MetaFieldDTO;
import io.softa.starter.metadata.controller.dto.MetaModelDTO;

/**
 * Mapping helpers from in-memory metadata ({@link MetaModel} / {@link MetaField})
 * to controller DTOs. Pure functions — no Spring dependencies — so they can be
 * reused from multiple service entry points (e.g. {@link MetadataServiceImpl#getMetaModelDTO}
 * and {@code resolveCascadedPaths}) without going through the bean.
 */
public final class MetadataDtoMapper {

    public static MetaFieldDTO toFieldDTO(MetaField metaField) {
        MetaFieldDTO fieldDTO = new MetaFieldDTO();
        fieldDTO.setLabel(metaField.getLabel());
        fieldDTO.setFieldName(metaField.getFieldName());
        fieldDTO.setModelName(metaField.getModelName());
        fieldDTO.setFieldType(metaField.getFieldType());
        fieldDTO.setDescription(metaField.getDescription());
        fieldDTO.setRequired(metaField.isRequired());
        fieldDTO.setLength(metaField.getLength());
        fieldDTO.setScale(metaField.getScale());
        fieldDTO.setDefaultValue(metaField.getDefaultValueObject());
        fieldDTO.setReadonly(metaField.isReadonly());
        fieldDTO.setHidden(metaField.isHidden());
        fieldDTO.setTranslatable(metaField.isTranslatable());
        fieldDTO.setCopyable(metaField.isCopyable());
        fieldDTO.setUnsearchable(metaField.isUnsearchable());
        fieldDTO.setComputed(metaField.isComputed());
        fieldDTO.setDynamic(metaField.isDynamic());
        fieldDTO.setEncrypted(metaField.isEncrypted());
        fieldDTO.setAutoSequence(metaField.isAutoSequence());
        fieldDTO.setOptionSetCode(metaField.getOptionSetCode());
        fieldDTO.setRelatedModel(metaField.getRelatedModel());
        fieldDTO.setRelatedField(metaField.getRelatedField());
        fieldDTO.setJoinModel(metaField.getJoinModel());
        fieldDTO.setJoinLeft(metaField.getJoinLeft());
        fieldDTO.setJoinRight(metaField.getJoinRight());
        fieldDTO.setCascadedField(metaField.getCascadedField());
        fieldDTO.setFilters(metaField.getFilters());
        fieldDTO.setMaskingType(metaField.getMaskingType());
        fieldDTO.setWidgetType(metaField.getWidgetType());
        fieldDTO.setCountries(metaField.getCountries());
        fieldDTO.setOnDelete(metaField.getOnDelete());
        fieldDTO.setConstraints(metaField.getConstraints());
        List<String> lookupFields = LookupKeys.of(metaField);
        fieldDTO.setLookupFields(lookupFields.isEmpty() ? null : lookupFields);
        return fieldDTO;
    }

    public static MetaModelDTO toModelDTO(String modelName) {
        MetaModel metaModel = ModelManager.getModel(modelName);
        MetaModelDTO dto = new MetaModelDTO();
        dto.setLabel(metaModel.getLabel());
        dto.setModelName(metaModel.getModelName());
        dto.setDescription(metaModel.getDescription());
        dto.setDisplayName(metaModel.getDisplayName());
        dto.setSearchName(metaModel.getSearchName());
        dto.setDefaultOrder(metaModel.getDefaultOrder());
        dto.setTimeline(metaModel.isTimeline());
        dto.setVersionLock(metaModel.isVersionLock());
        dto.setCopyable(metaModel.isCopyable());
        dto.setEnableAuditLog(ModelManager.isAuditLogEnabled(modelName));
        dto.setProjection(metaModel.isProjection());
        // By label, ignoring case — the name a person reads — so a client listing the model's fields
        // shows them alphabetically however the model was written or grown; a field without a label
        // goes by its name. The registry holds them unordered.
        List<MetaField> fields = ModelManager.getModelFields(modelName).stream()
                .sorted(Comparator.comparing(MetadataDtoMapper::sortName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(MetaField::getFieldName))
                .toList();
        Map<String, MetaFieldDTO> fieldMap = new LinkedHashMap<>();
        fields.forEach(field -> fieldMap.put(field.getFieldName(), toFieldDTO(field)));
        dto.setModelFields(fieldMap);
        return dto;
    }

    private static String sortName(MetaField field) {
        String label = field.getLabel();
        return label == null || label.isBlank() ? field.getFieldName() : label;
    }

    private MetadataDtoMapper() { }
}
