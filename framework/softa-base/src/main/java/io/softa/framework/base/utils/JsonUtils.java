package io.softa.framework.base.utils;

import io.softa.framework.base.exception.JSONException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON Object Mapper
 */
public class JsonUtils {
    private static JsonMapper mapper;

    /**
     * Get JsonMapper instance from the Spring Context.
     * If the Spring Context is not available, create a new JsonMapper instance.
     * @return JsonMapper instance
     */
    public static JsonMapper getMapper() {
        if (mapper != null) {
            return mapper;
        } else if (SpringContextUtils.existApplicationContext()) {
            mapper = SpringContextUtils.getBeanByClass(JsonMapper.class);
        } else {
            return JsonMapper.builder().build();
        }
        return mapper;
    }

    /**
     * Object to JSON string
     * @param object object instance
     * @return JSON String
     */
    public static String objectToString(Object object) {
        if (object instanceof String str) {
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
        if (json == null) {
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
        if (json == null) {
            return null;
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
        if (json == null) {
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
