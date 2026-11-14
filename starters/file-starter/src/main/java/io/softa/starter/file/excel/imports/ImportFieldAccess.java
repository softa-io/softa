package io.softa.starter.file.excel.imports;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.file.enums.ImportRule;

/**
 * Which columns of an import the caller may not write.
 *
 * <p>A sensitive field set decides who may write a field, and the write path enforces it: a value in a
 * field no role of the caller's grants is refused when the row is saved. The import asked nothing of
 * the sort before that. Its template still offered the column, still marked it required when the
 * template said so, and so a caller without the set was told to fill a column they were then refused
 * for filling — a file that could not succeed either way. The template, the field list the import
 * dialog shows and the import itself now leave such a column out, which is what the record's own form
 * already does: a field the caller may not write is not on it.
 *
 * <p>Asked of the field a column finally lands on — {@code empBankAccountId.methodOfPayment} is the
 * bank account's {@code methodOfPayment}, not a field of the employee. Admins and consultants are
 * blocked from nothing, so their templates are unchanged.
 */
@Component
public class ImportFieldAccess {

    private final PermissionService permissionService;

    public ImportFieldAccess(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * The template's columns, by their field path, that the caller may not write under this rule.
     *
     * <p>A rule that creates and updates is asked both ways, and a field refused either way is left out:
     * the file decides row by row which of the two happens, and a column has to work for both.
     *
     * @param modelName  the model the template imports
     * @param rule       the template's import rule; null is treated as create-or-update
     * @param fieldPaths the template's columns as field paths, possibly dotted
     * @return the paths the caller may not write; empty for a caller blocked from nothing
     */
    public Set<String> withheld(String modelName, ImportRule rule, Collection<String> fieldPaths) {
        Set<String> withheld = new HashSet<>();
        if (fieldPaths == null || fieldPaths.isEmpty()) {
            return withheld;
        }
        List<AccessType> writes = writesOf(rule);
        Map<String, Set<String>> blockedByModel = new HashMap<>();
        Function<String, Set<String>> blockedOf = model -> blockedByModel.computeIfAbsent(model, m -> {
            Set<String> blocked = new HashSet<>();
            writes.forEach(access -> blocked.addAll(permissionService.getUserBlockedModelFields(m, access)));
            return blocked;
        });
        for (String path : fieldPaths) {
            MetaField target = landingField(modelName, path);
            if (target != null && blockedOf.apply(target.getModelName()).contains(target.getFieldName())) {
                withheld.add(path);
            }
        }
        return withheld;
    }

    private static List<AccessType> writesOf(ImportRule rule) {
        if (rule == ImportRule.ONLY_CREATE) {
            return List.of(AccessType.CREATE);
        }
        if (rule == ImportRule.ONLY_UPDATE) {
            return List.of(AccessType.UPDATE);
        }
        return List.of(AccessType.CREATE, AccessType.UPDATE);
    }

    /**
     * The field a column is written into: the last one along its path. A longer path whose leading
     * relation is not one-to-one names a lookup — the value finds a row by its key and is written as a
     * reference on the first field — so that first field is the one written.
     */
    static MetaField landingField(String modelName, String path) {
        String[] parts = StringUtils.split(path, ".");
        if (parts == null || parts.length == 0 || !ModelManager.existModel(modelName)) {
            return null;
        }
        // Looked up leniently: a template column can outlive its field (renamed or removed), and such a
        // column fails on its own when the file is imported. Refusing it here would take down every
        // surface that lists the template's columns — the import dialog included — for every caller.
        MetaField first = ModelManager.getModelFieldOrNull(modelName, parts[0]);
        if (first == null) {
            return null;
        }
        if (parts.length == 1 || first.getFieldType() != FieldType.ONE_TO_ONE) {
            return first;
        }
        MetaField field = first;
        String model = modelName;
        for (int i = 0; i < parts.length; i++) {
            field = ModelManager.getModelFieldOrNull(model, parts[i]);
            if (field == null) {
                return null;
            }
            if (i < parts.length - 1) {
                if (field.getFieldType() != FieldType.ONE_TO_ONE || StringUtils.isBlank(field.getRelatedModel())) {
                    return field;
                }
                model = field.getRelatedModel();
            }
        }
        return field;
    }
}
