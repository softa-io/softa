package io.softa.starter.studio.template.ddl.context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.meta.entity.DesignModelIndex;
import io.softa.starter.studio.release.dto.RowChangeDTO;

import static io.softa.framework.orm.constant.ModelConstant.ID;
import static io.softa.framework.orm.constant.ModelConstant.SLICE_ID_COLUMN;

/**
 * Builder utilities for template-friendly DDL contexts.
 */
public final class DdlContextBuilder {

    private DdlContextBuilder() {}

    public static ModelDdlCtx fromCreatedModel(DesignModel designModel) {
        ModelDdlCtx model = baseModel(designModel);
        if (designModel.getModelFields() != null) {
            designModel.getModelFields().stream()
                    .filter(DdlContextBuilder::isStoredField)
                    .map(field -> fromField(field, model.getPkColumn(), model.isAutoIncrementPrimaryKey()))
                    .forEach(model.getCreatedFields()::add);
        }
        if (designModel.getModelIndexes() != null) {
            designModel.getModelIndexes().stream()
                    .map(DdlContextBuilder::fromIndex)
                    .forEach(model.getCreatedIndexes()::add);
        }
        return model;
    }

    public static ModelDdlCtx baseModel(DesignModel designModel) {
        ModelDdlCtx model = new ModelDdlCtx();
        model.setModelName(designModel.getModelName());
        model.setLabelName(designModel.getLabelName());
        model.setDescription(designModel.getDescription());
        model.setTableName(resolveTableName(designModel.getTableName(), designModel.getModelName()));
        model.setPkColumn(resolvePkColumn(Boolean.TRUE.equals(designModel.getTimeline())));
        model.setIdStrategy(designModel.getIdStrategy());
        model.setAutoIncrementPrimaryKey(isAutoIncrementPrimaryKey(designModel.getIdStrategy()));
        return model;
    }

    public static ModelDdlCtx fromModelData(Map<String, Object> data) {
        ModelDdlCtx model = new ModelDdlCtx();
        model.setModelName(asString(data.get("modelName")));
        model.setLabelName(asString(data.get("labelName")));
        model.setDescription(asString(data.get("description")));
        model.setTableName(resolveTableName(asString(data.get("tableName")), model.getModelName()));
        model.setPkColumn(resolvePkColumn(asBoolean(data.get("timeline"))));
        IdStrategy idStrategy = asIdStrategy(data.get("idStrategy"));
        model.setIdStrategy(idStrategy);
        model.setAutoIncrementPrimaryKey(isAutoIncrementPrimaryKey(idStrategy));
        return model;
    }

    public static ModelDdlCtx placeholderModel(String modelName) {
        ModelDdlCtx model = new ModelDdlCtx();
        model.setModelName(modelName);
        model.setTableName(resolveTableName(null, modelName));
        model.setPkColumn(resolvePkColumn(false));
        model.setAutoIncrementPrimaryKey(false);
        return model;
    }

    public static FieldDdlCtx fromField(DesignField designField, String pkColumn, boolean autoIncrementPrimaryKey) {
        FieldDdlCtx field = new FieldDdlCtx();
        field.setFieldName(designField.getFieldName());
        field.setColumnName(designField.getColumnName());
        field.setLabelName(designField.getLabelName());
        field.setDescription(designField.getDescription());
        field.setFieldType(designField.getFieldType());
        field.setLength(designField.getLength());
        field.setScale(designField.getScale());
        field.setRequired(Boolean.TRUE.equals(designField.getRequired()));
        field.setAutoIncrement(isAutoIncrementField(designField.getColumnName(), pkColumn, autoIncrementPrimaryKey));
        field.setDefaultValue(designField.getDefaultValue());
        return field;
    }

    public static FieldDdlCtx fromFieldData(Map<String, Object> data, String pkColumn, boolean autoIncrementPrimaryKey) {
        FieldDdlCtx field = new FieldDdlCtx();
        String columnName = asString(data.get("columnName"));
        field.setFieldName(asString(data.get("fieldName")));
        field.setColumnName(columnName);
        field.setLabelName(asString(data.get("labelName")));
        field.setDescription(asString(data.get("description")));
        field.setFieldType(asFieldType(data.get("fieldType")));
        field.setLength(asInteger(data.get("length")));
        field.setScale(asInteger(data.get("scale")));
        field.setRequired(asBoolean(data.get("required")));
        field.setAutoIncrement(isAutoIncrementField(columnName, pkColumn, autoIncrementPrimaryKey));
        field.setDefaultValue(asStringValue(data.get("defaultValue")));
        return field;
    }

    public static IndexDdlCtx fromIndex(DesignModelIndex designModelIndex) {
        IndexDdlCtx index = new IndexDdlCtx();
        index.setIndexName(designModelIndex.getIndexName());
        if (designModelIndex.getIndexFields() != null) {
            index.setColumns(List.copyOf(designModelIndex.getIndexFields()));
        }
        index.setUnique(Boolean.TRUE.equals(designModelIndex.getUniqueIndex()));
        return index;
    }

    public static IndexDdlCtx fromIndexData(Map<String, Object> data) {
        IndexDdlCtx index = new IndexDdlCtx();
        index.setIndexName(asString(data.get("indexName")));
        index.setUnique(asBoolean(data.get("uniqueIndex")));
        index.setColumns(asStringList(data.get("indexFields")));
        return index;
    }

    public static String resolvePkColumn(boolean timeline) {
        return timeline ? SLICE_ID_COLUMN : ID;
    }

    public static boolean isAutoIncrementPrimaryKey(IdStrategy idStrategy) {
        return idStrategy == IdStrategy.DB_AUTO_ID;
    }

    public static boolean isAutoIncrementField(String columnName, String pkColumn, boolean autoIncrementPrimaryKey) {
        return autoIncrementPrimaryKey && pkColumn != null && pkColumn.equals(columnName);
    }

    public static boolean isStoredField(DesignField designField) {
        return designField != null && isStoredField(designField.getFieldType(), Boolean.TRUE.equals(designField.getDynamic()));
    }

    public static boolean isStoredField(FieldType fieldType, boolean dynamic) {
        return !dynamic && fieldType != null && !FieldType.TO_MANY_TYPES.contains(fieldType);
    }

    public static Map<String, Object> buildPreviousData(RowChangeDTO rowChangeDTO) {
        Map<String, Object> previousData = new HashMap<>(rowChangeDTO.getCurrentData());
        previousData.putAll(rowChangeDTO.getDataBeforeChange());
        return previousData;
    }

    public static String asString(Object value) {
        return value instanceof String str ? str : value != null ? String.valueOf(value) : null;
    }

    public static IdStrategy asIdStrategy(Object value) {
        if (value instanceof IdStrategy idStrategy) {
            return idStrategy;
        }
        if (value instanceof String str) {
            for (IdStrategy idStrategy : IdStrategy.values()) {
                if (idStrategy.getType().equals(str) || idStrategy.name().equals(str)) {
                    return idStrategy;
                }
            }
        }
        return null;
    }

    public static FieldType asFieldType(Object value) {
        if (value instanceof FieldType fieldType) {
            return fieldType;
        }
        if (value instanceof String str && !str.isBlank()) {
            return FieldType.of(str);
        }
        return null;
    }

    public static String asStringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    public static Integer asInteger(Object value) {
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            return Integer.valueOf(str);
        }
        return null;
    }

    public static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String str) {
            return "true".equalsIgnoreCase(str) || "1".equals(str);
        }
        return false;
    }

    public static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static String resolveTableName(String tableName, String modelName) {
        if (tableName != null && !tableName.isBlank()) {
            return tableName;
        }
        return modelName != null ? StringTools.toUnderscoreCase(modelName) : null;
    }
}
