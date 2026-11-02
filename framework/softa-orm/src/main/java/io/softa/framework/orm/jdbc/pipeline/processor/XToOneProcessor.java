package io.softa.framework.orm.jdbc.pipeline.processor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.SubQuery;
import io.softa.framework.orm.entity.AbstractModel;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.utils.DisplayNames;
import io.softa.framework.orm.vo.ModelReference;

/**
 * ManyToOne/OneToOne field processor.
 * Get the displayName of ManyToOne/OneToOne field.
 */
@Slf4j
public class XToOneProcessor extends BaseProcessor {

    private ConvertType convertType = ConvertType.TYPE_CAST;
    private SubQuery subQuery;

    /**
     * Constructor of the input data field processor object.
     *
     * @param metaField Field metadata object
     * @param accessType Access type
     */
    public XToOneProcessor(MetaField metaField, AccessType accessType) {
        super(metaField, accessType);
    }

    /**
     * Constructor of the ManyToOne/OneToOne field processor object.
     *
     * @param metaField Field metadata object
     * @param accessType Access type
     * @param flexQuery flexQuery object
     */
    public XToOneProcessor(MetaField metaField, AccessType accessType, FlexQuery flexQuery) {
        super(metaField, accessType);
        this.convertType = flexQuery.getConvertType();
        this.subQuery = flexQuery.extractSubQuery(metaField.getFieldName());
    }

    /**
     * Process one inputting row, format ID field.
     *
     * @param row        data row
     */
    @Override
    public void processInputRow(Map<String, Object> row) {
        boolean isContain = row.containsKey(fieldName);
        checkReadonly(isContain);
        Object value = row.get(fieldName);
        if (isContain && value != null) {
            // A TO_ONE FK stores the related row's id. A ModelReference round-trip
            // carries that id under the `id` key.
            Object key;
            if (value instanceof Map<?, ?> mapValue && mapValue.containsKey(ModelConstant.ID)) {
                key = mapValue.get(ModelConstant.ID);
            } else if (value instanceof AbstractModel entity) {
                key = entity.getId();
            } else {
                key = value;
            }
            row.put(fieldName, IdUtils.formatId(metaField.getRelatedModel(), (Serializable) key));
        } else if (AccessType.CREATE.equals(accessType)) {
            checkRequired(value);
            row.computeIfAbsent(fieldName, k -> metaField.getDefaultValueObject());
        } else if (isContain) {
            checkRequired(null);
        }
    }

    /**
     * Expand the ManyToOne/OneToOne field with displayName, or related model row according to subQuery.
     *
     * @param rows Data list
     * @param relatedRowMap related model row map: {key: {field: value}}
     */
    public void batchProcessOutputRows(List<Map<String, Object>> rows, Map<Serializable, Map<String, Object>> relatedRowMap) {
        if (subQuery != null) {
            // When the subQuery is not null, assign the subQuery result to the ManyToOne/OneToOne field directly.
            rows.forEach(row -> {
                Serializable key = (Serializable) row.get(fieldName);
                key = IdUtils.formatId(metaField.getRelatedModel(), key);
                row.put(fieldName, relatedRowMap.get(key));
            });
        } else if (ConvertType.EXPAND_TYPES.contains(convertType)) {
            // If subQuery is null, but the result need to be expanded, fill in the ManyToOne/OneToOne with displayName.
            Map<Serializable, String> displayNameMap = this.getDisplayNameMap(relatedRowMap);
            rows.forEach(row -> processOutputRow(row, displayNameMap));
        }
    }

    /**
     * Fill in the displayName of ManyToOne/OneToOne field.
     *
     * @param row row data
     * @param displayNameMap the displayName map of the related model: {key: displayName}
     */
    public void processOutputRow(Map<String, Object> row, Map<Serializable, String> displayNameMap) {
        if (!row.containsKey(fieldName) || row.get(fieldName) == null) {
            return;
        }
        Serializable key = (Serializable) row.get(fieldName);
        Serializable formattedId = IdUtils.formatId(metaField.getRelatedModel(), key);
        if (formattedId == null) {
            log.warn("Model data {}(id={}) with field {}={}, the field value not exist in related model {}!",
                    metaField.getModelName(), row.get(ModelConstant.ID), fieldName, key, metaField.getRelatedModel());
        }
        Object value = displayNameMap.get(formattedId);
        if (ConvertType.REFERENCE.equals(convertType)) {
            value = ModelReference.of(formattedId, (String) value);
        }
        row.put(fieldName, value);
    }

    /**
     * Get the displayName map of the related model: {id: displayName}
     *
     * @param relatedValueMap Field values of the related model {id: {fieldName: Value}}
     * @return displayNameMap {id: displayName}
     */
    private Map<Serializable, String> getDisplayNameMap(Map<Serializable, Map<String, Object>> relatedValueMap) {
        Map<Serializable, String> displayNameMap = new HashMap<>();
        List<String> displayFields = ModelManager.getModelDisplayName(metaField.getRelatedModel());
        for (Map.Entry<Serializable, Map<String, Object>> value : relatedValueMap.entrySet()) {
            displayNameMap.put(value.getKey(), DisplayNames.of(value.getValue(), displayFields));
        }
        return displayNameMap;
    }
}
