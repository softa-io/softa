package io.softa.framework.base.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.softa.framework.base.config.JacksonConfig;
import io.softa.framework.base.exception.JSONException;

/**
 * JSON Object Mapper
 */
public class JsonUtils {
    private static JsonMapper mapper;

    /**
     * Get JsonMapper instance from the Spring Context.
     * If the Spring Context is not available, create a new JsonMapper instance under the same global policy.
     * @return JsonMapper instance
     */
    public static JsonMapper getMapper() {
        if (mapper != null) {
            return mapper;
        } else if (SpringContextUtils.existApplicationContext()) {
            mapper = SpringContextUtils.getBeanByClass(JsonMapper.class);
        } else {
            return JacksonConfig.applyGlobalPolicy(JsonMapper.builder()).build();
        }
        return mapper;
    }

    /**
     * Object to JSON string
     * @param object object instance
     * @return JSON String
     */
    public static String objectToString(Object object) {
        if (object == null) {
            return null;
        } else if (object instanceof String str) {
            return str;
        }
        try {
            return getMapper().writeValueAsString(object);
        } catch (JacksonException e) {
            throw new JSONException("JSON serialization failure: {0}", object.toString(), e);
        }
    }

    /**
     * String to object with Class
     * @param json JSON string
     * @param tClass object type
     * @return object instance
     */
    public static <T> T stringToObject(String json, Class<T> tClass) {
        if (json == null || json.isBlank()) {
            return null;
        } else if (tClass == String.class) {
            return Cast.of(json);
        }
        try {
            return getMapper().readValue(json, tClass);
        } catch (JacksonException e) {
            throw new JSONException("JSON deserialization failure: {0}", LogUtils.splitLog(json), e);
        }
    }

    /**
     * String to Map or List Object
     * @param json JSON string
     * @param valueTypeRef: such as `new TypeReference<List<Object>>() {}` or `new TypeReference<Map<String, Object>>() {}`
     * @return Map or List object
     */
    public static <T> T stringToObject(String json, TypeReference<T> valueTypeRef) {
        return stringToObject(json, valueTypeRef, null);
    }

    /**
     * String to Map or List Object
     * @param json JSON string
     * @param valueTypeRef: such as `new TypeReference<List<Object>>() {}` or `new TypeReference<Map<String, Object>>() {}`
     * @param defaultValue default value when JSON is null or blank
     * @return Map or List object
     */
    public static <T> T stringToObject(String json, TypeReference<T> valueTypeRef, T defaultValue) {
        if (json == null || json.isBlank()) {
            return defaultValue;
        } else if (valueTypeRef.getType().equals(String.class)) {
            return Cast.of(json);
        }
        try {
            return getMapper().readValue(json, valueTypeRef);
        } catch (JacksonException e) {
            throw new JSONException("JSON string deserializable failure: {0} ", LogUtils.splitLog(json), e);
        }
    }

    /**
     * String to object with JavaType
     * @param json JSON string
     * @param javaType JavaType
     * @return object instance
     */
    public static <T> T stringToObject(String json, JavaType javaType) {
        if (json == null || json.isBlank()) {
            return null;
        } else if (javaType.getRawClass() == String.class) {
            return Cast.of(json);
        }
        try {
            return getMapper().readValue(json, javaType);
        } catch (JacksonException e) {
            throw new JSONException("JSON deserialization failure: {0}", LogUtils.splitLog(json), e);
        }
    }

    /**
     * String to JsonNode with floating-point numbers read as BigDecimal,
     * so an amount keeps the scale it was written with: {@code 502.00} stays {@code 502.00}, not {@code 502.0}.
     * @param json JSON string
     * @return JsonNode, null when the string is blank
     */
    public static JsonNode stringToJsonNodeWithBigDecimalFloats(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return getMapper().reader(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(json);
        } catch (JacksonException e) {
            throw new JSONException("JSON deserialization failure: {0}", LogUtils.splitLog(json), e);
        }
    }

    /**
     * Object to JsonNode.
     * @param object object
     * @return JsonNode
     */
    public static JsonNode objectToJsonNode(Object object) {
        try {
            return getMapper().readValue(getMapper().writeValueAsString(object), JsonNode.class);
        } catch (JacksonException e) {
            throw new JSONException(e.getMessage(), e);
        }
    }

    /**
     * JsonNode to Map or List Object
     * @param jsonNode JsonNode
     * @param valueTypeRef: such as `new TypeReference<List<Object>>() {}` or `new TypeReference<Map<String, Object>>() {}`
     * @return Map or List object
     */
    public static <T> T jsonNodeToObject(JsonNode jsonNode, TypeReference<T> valueTypeRef) {
        try {
            JsonParser jsonParser = getMapper().treeAsTokens(jsonNode);
            return getMapper().readValue(jsonParser, valueTypeRef);
        } catch (JacksonException e) {
            throw new JSONException(e.getMessage(), e);
        }
    }

    /**
     * JsonNode to List, Map or String.
     * @param jsonNode JsonNode
     * @return List, Map or String object
     */
    public static Object jsonNodeToObject(JsonNode jsonNode) {
        if (null == jsonNode) {
            return null;
        }
        if (jsonNode.isArray()) {
            return getMapper().convertValue(jsonNode, ArrayList.class);
        } else if (jsonNode.isObject()) {
            return getMapper().convertValue(jsonNode, Map.class);
        } else {
            return jsonNode.asString();
        }
    }

    /**
     * JsonNode to specified type object.
     * @param jsonNode JsonNode
     * @return object instance
     */
    public static <T> T jsonNodeToObject(JsonNode jsonNode, Class<T> tClass) {
        try {
            return jsonNode == null ? null : getMapper().treeToValue(jsonNode, tClass);
        } catch (JacksonException e) {
            throw new JSONException(e.getMessage(), e);
        }
    }

    /**
     * JsonNode to List<Long>
     * @param jsonNode JsonNode
     * @return List<Long> object
     */
    public static List<Long> jsonNodeToLongList(JsonNode jsonNode) {
        if (null == jsonNode) {
            return null;
        }
        if (jsonNode.isArray()) {
            return getMapper().convertValue(jsonNode, new TypeReference<>() {
            });
        }
        return null;
    }


    /**
     * JsonNode to List<String>
     * @param jsonNode JsonNode
     * @return List<String> object
     */
    public static List<String> jsonNodeToStringList(JsonNode jsonNode) {
        if (null == jsonNode) {
            return null;
        }
        if (jsonNode.isArray()) {
            return getMapper().convertValue(jsonNode, new TypeReference<>() {
            });
        }
        return null;
    }

    /**
     * Lenient JSON-array value → {@code List<String>}, for a value whose runtime type
     * varies by JDBC driver / dialect when a JSON column is read without an entity
     * class (the ModelService <em>Map</em> overload / 约定读): a JSON {@code String}
     * (e.g. MySQL JSON columns), an already-parsed {@link JsonNode}, or a
     * {@link Collection}. Handles all three and never throws.
     *
     * <p><b>Strict-string</b>: numeric / boolean / object elements are dropped — unlike
     * {@link #jsonNodeToStringList(JsonNode)} which uses {@code convertValue} and
     * stringifies everything. Returns {@code null} (not an empty list) when the value
     * is null / blank / not a non-empty string array, so callers can tell "absent"
     * from "present but empty".
     *
     * @see #toStringList(Object, boolean)
     */
    public static List<String> toStringList(Object raw) {
        return toStringList(raw, false);
    }

    /**
     * As {@link #toStringList(Object)}, but when {@code coerceNumeric} is true a numeric
     * element is kept as its string form — for id columns historically persisted as JSON
     * numbers.
     */
    public static List<String> toStringList(Object raw, boolean coerceNumeric) {
        JsonNode node = toArrayNode(raw);
        if (node == null) {
            return null;
        }
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode el : node) {
            if (el.isString()) {
                out.add(el.asString());
            } else if (coerceNumeric && el.isNumber()) {
                out.add(el.numberValue().toString());
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** Coerce a raw JSON-ish value (JsonNode / JSON String / Collection) into an array
     *  {@link JsonNode}, or {@code null} if it is null / blank / not a JSON array. Never throws. */
    private static JsonNode toArrayNode(Object raw) {
        if (raw == null) {
            return null;
        }
        JsonNode node;
        if (raw instanceof JsonNode jn) {
            node = jn;
        } else if (raw instanceof CharSequence cs) {
            String s = cs.toString().trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                node = stringToObject(s, JsonNode.class);
            } catch (Exception e) {
                return null;
            }
        } else if (raw instanceof Collection<?> col) {
            node = objectToJsonNode(col);
        } else {
            return null;
        }
        return (node != null && node.isArray()) ? node : null;
    }

    /**
     * JsonNode to Map<String, Object>
     * @param jsonNode JsonNode
     * @return Map<String, Object>
     */
    public static Map<String, Object> jsonNodeToMap(JsonNode jsonNode) {
        if (null == jsonNode) {
            return null;
        }
        return jsonNodeToObject(jsonNode, new TypeReference<>() {});
    }

}
