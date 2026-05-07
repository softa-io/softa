package io.softa.starter.metadata.service.impl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        fieldDTO.setLabelName(metaField.getLabelName());
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
        fieldDTO.setNonCopyable(metaField.isNonCopyable());
        fieldDTO.setUnsearchable(metaField.isUnsearchable());
        fieldDTO.setComputed(metaField.isComputed());
        fieldDTO.setDynamic(metaField.isDynamic());
        fieldDTO.setEncrypted(metaField.isEncrypted());
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
        return fieldDTO;
    }

    public static MetaModelDTO toModelDTO(String modelName) {
        MetaModel metaModel = ModelManager.getModel(modelName);
        MetaModelDTO dto = new MetaModelDTO();
        dto.setLabelName(metaModel.getLabelName());
        dto.setModelName(metaModel.getModelName());
        dto.setDescription(metaModel.getDescription());
        dto.setDisplayName(metaModel.getDisplayName());
        dto.setSearchName(metaModel.getSearchName());
        dto.setDefaultOrder(metaModel.getDefaultOrder());
        dto.setTimeline(metaModel.isTimeline());
        List<MetaField> fields = ModelManager.getModelFields(modelName);
        Map<String, MetaFieldDTO> fieldMap = fields.stream()
                .collect(Collectors.toMap(MetaField::getFieldName, MetadataDtoMapper::toFieldDTO));
        dto.setModelFields(fieldMap);
        return dto;
    }

    private MetadataDtoMapper() { }
}
