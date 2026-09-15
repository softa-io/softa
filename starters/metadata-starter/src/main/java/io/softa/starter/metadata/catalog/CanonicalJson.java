package io.softa.starter.metadata.catalog;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.softa.framework.base.utils.JsonUtils;

/**
 * One JSON text for one logical value: keys in ASCII order, nulls dropped, no whitespace.
 *
 * <p>The DTO columns of the catalog ({@code sys_field.constraints}) are compared and hashed as text —
 * by the diff engine deciding whether a row changed, and by the cross-lane checksum deciding whether
 * studio and runtime agree. Jackson's field order follows the declaration, a hand-written row follows
 * the hand, and a {@code null} member may or may not be written; any of those would turn the same
 * declaration into a "change". Canonicalizing at the two points where text is produced
 * ({@code Codecs#dto} on the way to the database, {@code CanonicalMetadataSerializer} on the way into
 * the hash) removes the question.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    /** Canonical text of an object, or null for null / an object with no non-null member. */
    public static String of(Object value) {
        if (value == null) {
            return null;
        }
        JsonNode canonical = canonicalize(JsonUtils.objectToJsonNode(value));
        if (canonical == null || (canonical.isObject() && canonical.isEmpty())) {
            return null;
        }
        return canonical.toString();
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode child = canonicalize(e.getValue());
                if (child != null) {
                    sorted.put(e.getKey(), child);
                }
            }
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            // Element order is semantic for a filter tree — keep it; only nulls inside objects drop.
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : node) {
                out.add(element.isNull() ? element : canonicalize(element));
            }
            return out;
        }
        return node;
    }
}
