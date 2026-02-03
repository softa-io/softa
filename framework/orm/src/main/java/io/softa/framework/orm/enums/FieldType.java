package io.softa.framework.orm.enums;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.JsonNode;
import com.google.common.collect.Sets;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.constant.EnvConstant;
import io.softa.framework.base.constant.StringConstant;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.dto.DTOFieldObject;

/**
 * Field type Enum.
 */
@Slf4j
@Getter
@AllArgsConstructor
public enum FieldType {
    // String, including long text
    STRING("String", "String", String.class, Types.VARCHAR),

    // Numeric
    INTEGER("Integer", "Integer", Integer.class, Types.INTEGER),
    LONG("Long", "Long", Long.class, Types.BIGINT),
    DOUBLE("Double", "Decimal", Double.class, Types.DOUBLE),
    BIG_DECIMAL("BigDecimal", "High-Precision Decimal", BigDecimal.class, Types.DECIMAL),

    // bool
    BOOLEAN("Boolean", "Yes/No", Boolean.class, Types.BOOLEAN),

    // time
    DATE("Date", "Date", LocalDate.class, Types.DATE),
    DATE_TIME("DateTime", "DateTime", LocalDateTime.class, Types.TIMESTAMP),
    TIME("Time", "Time", LocalTime.class, Types.TIME),

    // OptionList, MultiOption, MultiString, JSON, Filters, Orders
    OPTION("Option", "Single Option", String.class, Types.VARCHAR),
    MULTI_OPTION("MultiOption", "MultiOption", List.class, Types.VARCHAR),
    MULTI_STRING("MultiString", "MultiString", List.class, Types.VARCHAR),
    JSON("JSON", "JSON", JsonNode.class, Types.LONGVARCHAR),
    FILTERS("Filters", "Filters", Filters.class, Types.VARCHAR),
    ORDERS("Orders", "Orders", Orders.class, Types.VARCHAR),

    // DTO store the json string of DTO object, which is an implementation of DTOFieldObject interface
    DTO("DTO", "DTO", DTOFieldObject.class, Types.LONGVARCHAR),

    // File and MultiFile store the ids of FileRecord model
    FILE("File", "File", null, Types.VARCHAR),
    MULTI_FILE("MultiFile", "MultiFile", null, Types.VARCHAR),

    // Relational fields
    ONE_TO_ONE("OneToOne", "OneToOne", null, Types.BIGINT),
    MANY_TO_ONE("ManyToOne", "ManyToOne", null, Types.BIGINT),
    ONE_TO_MANY("OneToMany", "OneToMany", null, Types.NULL),
    MANY_TO_MANY("ManyToMany", "ManyToMany", null, Types.NULL);

    @JsonValue
    private final String type;
    private final String name;
    private final Class<?> javaType;
    private final int sqlType;

    // Relational field type set
    public static final Set<FieldType> RELATED_TYPES = Sets.immutableEnumSet(ONE_TO_ONE, MANY_TO_ONE, ONE_TO_MANY, MANY_TO_MANY);
    // OneToOne, ManyToOne
    public static final Set<FieldType> TO_ONE_TYPES = Sets.immutableEnumSet(ONE_TO_ONE, MANY_TO_ONE);
    // OneToMany, ManyToMany
    public static final Set<FieldType> TO_MANY_TYPES = Sets.immutableEnumSet(ONE_TO_MANY, MANY_TO_MANY);
    // Numeric field type set
    public static final Set<FieldType> NUMERIC_TYPES = Sets.immutableEnumSet(INTEGER, LONG, DOUBLE, BIG_DECIMAL);
    // Comparable field type set
    public static final Set<FieldType> COMPARABLE_TYPES = Sets.immutableEnumSet(INTEGER, LONG, DOUBLE, BIG_DECIMAL,
            DATE, DATE_TIME, TIME, STRING, OPTION);
    // Expandable field type set
    public static final Set<FieldType> EXPANDABLE_TYPES = Sets.immutableEnumSet(BOOLEAN, MANY_TO_ONE, ONE_TO_ONE, OPTION, MULTI_OPTION);
    // File type set
    public static final Set<FieldType> FILE_TYPES = Sets.immutableEnumSet(FILE, MULTI_FILE);

    /**
     * type map
     */
    private static final Map<String, FieldType> typeMap = Stream.of(values()).collect(Collectors.toMap(FieldType::getType, Function.identity()));

    /**
     * Get field type by string type
     *
     * @param type string
     * @return FieldType
     */
    public static FieldType of(String type) {
        Assert.notBlank(type, "Field type cannot be blank!");
        return typeMap.get(type);
    }

    /**
     * Convert string value to object value according to field type
     *
     * @param fieldType field type
     * @param value     string value
     * @return object value of field type
     */
    public static Object convertStringToFieldValue(FieldType fieldType, String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        // Remove the `'` and `"` at the beginning and end of the default value string.
        value = truncateDefaultValue(value);
        if (value.isBlank()) {
            // Compatible with "", '', " ", ' ' and other blank strings
            return StringConstant.EMPTY_STRING;
        }
        try {
            return switch (fieldType) {
                case INTEGER -> Integer.valueOf(value);
                case LONG -> Long.valueOf(value);
                case DOUBLE -> Double.valueOf(value);
                case BIG_DECIMAL -> new BigDecimal(value);
                case BOOLEAN -> value.equals("1") || value.equalsIgnoreCase(StringConstant.TRUE_STRING);
                // The `now` parameter (for the current time) is instantiated when the value is taken.
                case DATE -> EnvConstant.TIME_PARAMS.contains(value.toUpperCase()) ? EnvConstant.TODAY
                        : DateUtils.stringToDateObject(value, LocalDate.class);
                case DATE_TIME -> EnvConstant.NOW.equalsIgnoreCase(value) ? EnvConstant.NOW
                        : DateUtils.stringToDateObject(value, LocalDateTime.class);
                case TIME -> EnvConstant.NOW.equalsIgnoreCase(value) ? EnvConstant.NOW
                        : DateUtils.stringToDateObject(value, LocalTime.class);
                // Other cases are not converted
                default -> value;
            };
        } catch (Exception e) {
            throw new IllegalArgumentException("field.default.value.incorrect.{0}.{1}", fieldType.getType(), value, e);
        }
    }

    /**
     * Remove the `'`, `"` and spaces at the beginning and end of the default value string.
     * <p>
     * This method could be removed when the default value does not have this flag bit.
     *
     * @param value default value string
     * @return string after removing the `'`, `"` and spaces at the beginning and end
     */
    private static String truncateDefaultValue(String value) {
        if (value.startsWith("'") || value.startsWith("\"")) {
            value = value.substring(1);
        }
        if (value.endsWith("'") || value.endsWith("\"")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.trim();
    }
}
