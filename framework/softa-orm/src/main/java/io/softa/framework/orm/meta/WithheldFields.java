package io.softa.framework.orm.meta;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.FieldType;

/**
 * The fields withheld from a caller, beyond the sensitive fields none of their roles grants
 * ({@code PermissionService#getHiddenSensitiveFields}): a relation onto a model the caller can read
 * nothing of goes with them, since it could only offer a column, an export field and a filter that come
 * back empty.
 *
 * <p>One answer for every place that leaves such fields out — the metadata a screen is built from and
 * the hidden-field list it checks a declared field against. A field missing from the one but not named
 * by the other reads as a declaration error, so the two must never disagree.
 */
public final class WithheldFields {

    private WithheldFields() {
    }

    /**
     * {@code withheld} with the relations onto a model the caller can read nothing of added, under the
     * model that declares them.
     *
     * @param withheld model name → the sensitive fields withheld from the caller
     * @return model name → every field withheld from the caller; {@code withheld} itself when nothing is
     * withheld
     */
    public static Map<String, Set<String>> withRelations(Map<String, Set<String>> withheld) {
        if (withheld.isEmpty()) {
            return withheld;
        }
        Map<String, Set<String>> all = new TreeMap<>();
        withheld.forEach((model, fields) -> all.put(model, new TreeSet<>(fields)));
        for (String model : ModelManager.getModelNames()) {
            for (MetaField field : ModelManager.getModelFields(model)) {
                if (FieldType.RELATED_TYPES.contains(field.getFieldType())
                        && nothingLeftToRead(field.getRelatedModel(), model, withheld)) {
                    all.computeIfAbsent(model, k -> new TreeSet<>()).add(field.getFieldName());
                }
            }
        }
        return all;
    }

    /**
     * Whether a field of {@code model} is withheld from the caller: one of its withheld sensitive fields,
     * or a relation onto a model the caller can read nothing of ({@link #nothingLeftToRead}).
     */
    public static boolean isWithheld(String model, String fieldName, FieldType fieldType, String relatedModel,
                                     Map<String, Set<String>> withheld) {
        if (withheld.getOrDefault(model, Set.of()).contains(fieldName)) {
            return true;
        }
        return FieldType.RELATED_TYPES.contains(fieldType) && nothingLeftToRead(relatedModel, model, withheld);
    }

    /**
     * Whether every field of {@code related} that carries content is withheld from the caller, read as
     * reached from {@code owner}. Plumbing does not count: the reserved fields (id, tenant, version,
     * audit, soft delete) and a to-one back onto the owner, which names the record the caller came from.
     */
    static boolean nothingLeftToRead(String related, String owner, Map<String, Set<String>> withheld) {
        Set<String> hidden = related == null ? null : withheld.get(related);
        if (hidden == null || hidden.isEmpty()) {
            return false;
        }
        // Only a model seen to withhold something is empty to the caller, not one with no fields known.
        boolean withholdsAny = false;
        for (MetaField field : ModelManager.getModelFields(related)) {
            String name = field.getFieldName();
            if (hidden.contains(name)) {
                withholdsAny = true;
                continue;
            }
            boolean backToOwner = FieldType.TO_ONE_TYPES.contains(field.getFieldType())
                    && owner.equals(field.getRelatedModel());
            if (!ModelConstant.RESERVED_KEYWORD.contains(name) && !backToOwner) {
                return false;
            }
        }
        return withholdsAny;
    }
}
