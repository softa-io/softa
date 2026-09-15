package io.softa.starter.metadata.checksum;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.dto.DTOFieldObject;
import io.softa.starter.metadata.catalog.CanonicalJson;

/**
 * Deterministic canonical serialization of a metadata aggregate's key attributes,
 * used to compute the per-aggregate checksum. The SAME logical state on
 * the studio ({@code design_*}) side and the runtime ({@code sys_*}) side MUST
 * serialize to a byte-identical string, so this is the single shared serializer
 * that both lanes call — divergence would cause a false-same (silent missed change)
 * or a false-diff (needless payload).
 *
 * <p>Rules:
 * <ul>
 *   <li>only allow-listed keys are included, in ASCII-ascending key order;</li>
 *   <li>a key absent from the map hashes identically to an explicit {@code null}
 *       (an omitted vs. null-valued attribute must not diverge);</li>
 *   <li>values are type-tagged so the string {@code "1"} never collides with the
 *       number {@code 1} (or the boolean {@code true} with the string {@code "true"});</li>
 *   <li>list elements keep their declared order — it is semantic for
 *       {@code displayName} / {@code businessKey} / {@code indexFields}.</li>
 * </ul>
 */
public final class CanonicalMetadataSerializer {

    private CanonicalMetadataSerializer() {
    }

    /**
     * Render the allow-listed attributes of {@code attrs} into a canonical string.
     *
     * @param attrs        the aggregate's attributes (a {@code null} map is treated as all-null)
     * @param keyAllowList the keys that affect the runtime schema/behavior; everything
     *                     else (id, audit, surrogate FKs, the checksum itself)
     *                     is excluded by being absent from this list
     */
    public static String canonical(Map<String, Object> attrs, List<String> keyAllowList) {
        StringBuilder sb = new StringBuilder();
        for (String key : new TreeSet<>(keyAllowList)) {
            sb.append(key).append('=');
            encode(attrs == null ? null : attrs.get(key), sb);
            sb.append(';');
        }
        return sb.toString();
    }

    private static void encode(Object value, StringBuilder sb) {
        switch (value) {
            case null -> sb.append('∅');                       // ∅ — distinct, unforgeable null marker
            case Boolean b -> sb.append("b:").append(b);
            case Enum<?> e -> sb.append("e:").append(e.name());
            case Number n -> sb.append("n:").append(n);             // metadata numerics are Integer/Long — no float drift
            // A DTO column hashes as its canonical JSON, so key order and spelling in the stored text
            // cannot move the checksum. It arrives as the record when read through the catalog codec and
            // as a JsonNode when read through modelService.searchList (the JSON processor parses the
            // text); the default branch would hash toString() of either.
            case DTOFieldObject d -> appendJson(CanonicalJson.of(d), sb);
            case JsonNode node -> appendJson(CanonicalJson.of(node), sb);
            case List<?> list -> {
                sb.append('[');
                boolean first = true;
                for (Object el : list) {
                    if (!first) {
                        sb.append(',');
                    }
                    encode(el, sb);
                    first = false;
                }
                sb.append(']');
            }
            default -> sb.append("s:").append(value);
        }
    }

    private static void appendJson(String canonical, StringBuilder sb) {
        if (canonical == null) {
            sb.append('∅');
        } else {
            sb.append("j:").append(canonical);
        }
    }
}
