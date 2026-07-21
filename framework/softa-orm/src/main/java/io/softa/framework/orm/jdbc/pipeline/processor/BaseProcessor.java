package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.*;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.Cast;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.PatchType;
import io.softa.framework.orm.meta.MetaField;

/**
 * Base field processor class
 */
public abstract class BaseProcessor implements FieldProcessor {

    protected final String modelName;
    protected final String fieldName;
    protected final MetaField metaField;
    protected final AccessType accessType;

    /**
     * Field processor object constructor
     *
     * @param metaField field metadata object
     */
    public BaseProcessor(MetaField metaField, AccessType accessType) {
        this.metaField = metaField;
        this.modelName = metaField.getModelName();
        this.fieldName = metaField.getFieldName();
        this.accessType = accessType;
    }

    /**
     * The readonly field cannot be assigned a value.
     * Like computed fields, an autoSequence field is system-written on CREATE:
     * SequenceProcessor runs earlier in the chain, rejects caller-provided values
     * on readonly fields itself, then fills the allocated value — which must pass
     * here. The exemption is CREATE-only so UPDATE keeps rejecting assignment.
     */
    protected void checkReadonly(boolean isContain) {
        if (metaField.isReadonly()
                && isContain
                && !metaField.isComputed()
                && !(metaField.isAutoSequence() && AccessType.CREATE.equals(accessType))
                && StringUtils.isBlank(metaField.getCascadedField())) {
            throw new IllegalArgumentException("Model field {0}:{1} is a readonly field and cannot be assigned!",
                    metaField.getModelName(), fieldName);
        }
    }

    /**
     * The required field cannot be assigned a null value.
     */
    protected void checkRequired(Object value) {
        if (metaField.isRequired() && value == null) {
            throw new IllegalArgumentException("Model field {0}:{1} is a required field and cannot be null!",
                    metaField.getModelName(), fieldName);
        }
    }

    /**
     * Check if the required field is set to null or empty.
     */
    protected void checkNotBlank(Object value) {
        if (metaField.isRequired() && (value == null || StringUtils.isBlank(value.toString()))) {
            throw new IllegalArgumentException("Model required field {0}:{1} cannot be empty!", metaField.getModelName(), fieldName);
        }
    }

    /**
     * Process single-row input data.
     *
     * @param row The single-row data to be created or updated
     */
    @Override
    public void processInputRow(Map<String, Object> row) {
        boolean isContain = row.containsKey(fieldName);
        checkReadonly(isContain);
        Object value = row.get(fieldName);
        if (AccessType.CREATE.equals(accessType)) {
            checkRequired(value);
            row.computeIfAbsent(fieldName, k -> metaField.getDefaultValueObject());
        } else if (isContain) {
            // Check if the required field is set to null.
            checkRequired(value);
        }
    }

    /**
     * Batch process input data
     *
     * @param rows The list of data to be created or updated
     */
    @Override
    public void batchProcessInputRows(List<Map<String, Object>> rows) {
        rows.forEach(this::processInputRow);
    }

    /**
     * Process single-row output data.
     *
     * @param row The single-row output data
     */
    @Override
    public void processOutputRow(Map<String, Object> row) {}

    /**
     * Batch process output data
     *
     * @param rows The list of output data
     */
    @Override
    public void batchProcessOutputRows(List<Map<String, Object>> rows) {
        rows.forEach(this::processOutputRow);
    }

    /**
     * Parse patch data of OneToMany/ManyToMany value object.
     */
    protected Map<PatchType, Object> parsePatchData(Map<?, ?> patchMap, Set<PatchType> allowedTypes) {
        Map<PatchType, Object> patchData = new EnumMap<>(PatchType.class);
        patchMap.forEach((key, value) -> {
            PatchType patchType = PatchType.of(String.valueOf(key));
            Assert.notNull(patchType, "Model field {0}:{1} has unknown patch operation: {2}", modelName, fieldName, key);
            Assert.isTrue(allowedTypes.contains(patchType),
                    "Model field {0}:{1} does not support patch operation: {2}", modelName, fieldName, patchType.getType());
            patchData.put(patchType, value);
        });
        return patchData;
    }

    /**
     * Get list value by patch type.
     */
    protected List<?> getPatchList(Map<PatchType, Object> patchData, PatchType patchType) {
        Object value = patchData.get(patchType);
        if (value == null) {
            return Collections.emptyList();
        }
        Assert.isTrue(value instanceof List<?>,
                "Model field {0}:{1} patch `{2}` value must be a list: {3}", modelName, fieldName, patchType.getType(), value);
        return Cast.of(value);
    }
}
