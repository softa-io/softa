package io.softa.starter.file.excel.export.support;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.softa.framework.orm.meta.LookupKeys;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

/**
 * The columns a dynamic export writes for the fields picked: each field as itself, except a
 * many-to-one looked up by a key other than the name it displays, which writes two columns side by
 * side — the name, then the key.
 *
 * <p>An export writes a relation as its display name, while an import matches a relation column by
 * its lookup key ({@link LookupKeys}). Where the two differ — a department named "Finance" is found
 * by its code "D001" — an exported sheet imported back unchanged failed on every row. The key column
 * is what the import maps by default, so the file goes back the way it came out; the name stays for
 * the person reading it. The headers say which column is which: "Department (Name)",
 * "Department (Code)".
 *
 * <p>A relation whose key is the very name it displays keeps its single column, as does a dotted
 * path: an import resolves one level, so a sub-record's own relation has no key column to read.
 */
public final class DynamicExportColumns {

    /** What the header of a relation's name column says the column holds. */
    public static final String NAME_SUFFIX = "Name";

    /** One column of the sheet: the field it reads, and the header it is written under. */
    public record Column(String field, String header) {
    }

    private DynamicExportColumns() {
    }

    /**
     * The columns for {@code picked}, in its order, each relation's key column right after its name.
     * A key path that was itself picked is not repeated.
     */
    public static List<Column> of(String modelName, List<String> picked) {
        Set<String> pickedFields = new HashSet<>(picked);
        List<Column> columns = new ArrayList<>();
        for (String fieldName : picked) {
            MetaField field = ModelManager.getLastFieldOfCascaded(modelName, fieldName);
            String keyField = fieldName.contains(".") ? null : keyBesideName(field);
            if (keyField == null) {
                columns.add(new Column(fieldName, field.getLabel()));
                continue;
            }
            columns.add(new Column(fieldName, header(field.getLabel(), NAME_SUFFIX)));
            String keyPath = fieldName + "." + keyField;
            if (!pickedFields.contains(keyPath)) {
                MetaField key = ModelManager.getModelField(field.getRelatedModel(), keyField);
                columns.add(new Column(keyPath, header(field.getLabel(), key.getLabel())));
            }
        }
        return columns;
    }

    /** The field a relation is looked up by, when that is not the name it displays; otherwise null. */
    static String keyBesideName(MetaField relation) {
        List<String> keys = LookupKeys.of(relation);
        if (keys.isEmpty()) {
            return null;
        }
        String key = keys.getFirst();
        List<String> displayName = ModelManager.getModelDisplayName(relation.getRelatedModel());
        return displayName.size() == 1 && displayName.getFirst().equals(key) ? null : key;
    }

    private static String header(String relationLabel, String part) {
        return relationLabel + " (" + part + ")";
    }
}
