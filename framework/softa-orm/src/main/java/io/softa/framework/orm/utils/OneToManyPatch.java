package io.softa.framework.orm.utils;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.enums.PatchType;

/**
 * A one-to-many field's value sent as a patch rather than as the rows it replaces the field with:
 * {@code {"Create": [rows], "Update": [rows, each with its id], "Delete": [ids]}}, each part optional, the keys read as
 * {@link PatchType#of} reads them. The one reading of the format: the write pipeline's ({@code OneToManyProcessor}),
 * and that of code working a value out before it is written — a preview of a record not saved yet, a form's onChange.
 *
 * @param createRows the rows the patch creates
 * @param updateRows the rows the patch updates, each with its id
 * @param deleteIds  the ids of the rows the patch deletes
 */
public record OneToManyPatch(List<?> createRows, List<?> updateRows, List<?> deleteIds) {

    private static final Set<PatchType> TYPES = EnumSet.of(PatchType.CREATE, PatchType.UPDATE, PatchType.DELETE);

    /**
     * Reads {@code patch}, refusing a key that is no one-to-many operation and a part that is not a list.
     *
     * @param modelName the model whose field the patch is for, as the refusals name it
     * @param fieldName the one-to-many field
     */
    public static OneToManyPatch parse(Map<?, ?> patch, String modelName, String fieldName) {
        Map<PatchType, List<?>> parts = new EnumMap<>(PatchType.class);
        patch.forEach((key, value) -> {
            PatchType type = PatchType.of(String.valueOf(key));
            Assert.notNull(type, "Model field {0}:{1} has unknown patch operation: {2}", modelName, fieldName, key);
            Assert.isTrue(TYPES.contains(type),
                    "Model field {0}:{1} does not support patch operation: {2}", modelName, fieldName, type.getType());
            if (value != null) {
                Assert.isTrue(value instanceof List<?>,
                        "Model field {0}:{1} patch `{2}` value must be a list: {3}", modelName, fieldName,
                        type.getType(), value);
                parts.put(type, (List<?>) value);
            }
        });
        return new OneToManyPatch(parts.getOrDefault(PatchType.CREATE, List.of()),
                parts.getOrDefault(PatchType.UPDATE, List.of()),
                parts.getOrDefault(PatchType.DELETE, List.of()));
    }
}
