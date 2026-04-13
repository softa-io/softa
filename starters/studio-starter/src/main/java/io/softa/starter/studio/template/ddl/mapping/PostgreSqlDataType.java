package io.softa.starter.studio.template.ddl.mapping;

import java.util.EnumMap;
import java.util.Map;

import io.softa.framework.orm.enums.FieldType;

/**
 * Default field type mapping for PostgreSQL database.
 */
public abstract class PostgreSqlDataType {
    public static final Map<FieldType, String> FIELD_TYPE_MAP = new EnumMap<>(FieldType.class);

    static {
        FIELD_TYPE_MAP.put(FieldType.STRING, "VARCHAR");
        FIELD_TYPE_MAP.put(FieldType.INTEGER, "INT");
        FIELD_TYPE_MAP.put(FieldType.LONG, "BIGINT");
        FIELD_TYPE_MAP.put(FieldType.DOUBLE, "NUMERIC");
        FIELD_TYPE_MAP.put(FieldType.BIG_DECIMAL, "NUMERIC");
        FIELD_TYPE_MAP.put(FieldType.OPTION, "VARCHAR");
        FIELD_TYPE_MAP.put(FieldType.BOOLEAN, "BOOLEAN");
        FIELD_TYPE_MAP.put(FieldType.DATE, "DATE");
        FIELD_TYPE_MAP.put(FieldType.DATE_TIME, "TIMESTAMP");
        FIELD_TYPE_MAP.put(FieldType.TIME, "TIME");
        FIELD_TYPE_MAP.put(FieldType.ONE_TO_ONE, "BIGINT");
        FIELD_TYPE_MAP.put(FieldType.MANY_TO_ONE, "BIGINT");
        FIELD_TYPE_MAP.put(FieldType.JSON, "TEXT");
        FIELD_TYPE_MAP.put(FieldType.DTO, "TEXT");
        FIELD_TYPE_MAP.put(FieldType.MULTI_STRING, "VARCHAR");
        FIELD_TYPE_MAP.put(FieldType.MULTI_OPTION, "VARCHAR");
        FIELD_TYPE_MAP.put(FieldType.FILTERS, "VARCHAR");
        FIELD_TYPE_MAP.put(FieldType.ORDERS, "VARCHAR");
        FIELD_TYPE_MAP.put(FieldType.FILE, "VARCHAR");
        FIELD_TYPE_MAP.put(FieldType.MULTI_FILE, "VARCHAR");
    }

    public static String getDbType(FieldType fieldType) {
        return FIELD_TYPE_MAP.get(fieldType);
    }
}
