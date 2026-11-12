package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

/**
 * The rules an import column's field path is held to, and those a template's columns are held to together —
 * read by the import as it builds its columns and by an import template's save check, so a template that saves
 * does not fail on a column when it is run.
 *
 * <p>An import path is not a read path. It may start on a to-many relation ({@code roleIds.code}: the cell
 * names the roles by code), it reaches at most two levels, and its third segment is allowed through exactly
 * one shape — see {@link #checkLookupPath}. These are the importer's own rules, so they live with the
 * importer rather than in the ORM's {@code FieldPaths}.
 *
 * <p>The messages are formatted by {@code MessageFormat}: an apostrophe in one starts a quoted run and
 * swallows every placeholder after it, so they are worded without one.
 */
public final class ImportFieldPaths {

    private ImportFieldPaths() {
    }

    /**
     * Why the column cannot be imported into {@code modelName}, or null when it can — the two checks the
     * import makes, in the order it makes them.
     */
    public static @Nullable String problem(String modelName, String fieldName) {
        if (StringUtils.isBlank(fieldName)) {
            return "The import field is empty.";
        }
        try {
            lastField(modelName, fieldName);
            if (fieldName.contains(".")) {
                checkLookupPath(modelName, fieldName);
            }
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * Why the columns cannot be imported together, or null when they can — the rules no column breaks on its own
     * ({@link #checkColumns}). A column's own path is {@link #problem}'s to judge.
     */
    public static @Nullable String columnsProblem(Collection<String> fieldNames) {
        try {
            checkColumns(fieldNames);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * The rules a template's columns are held to together, which no column breaks on its own:
     * <ul>
     *   <li>a relation is imported as it is or through a lookup, not both: {@code deptId} beside
     *       {@code deptId.code} would give the row two values for one field;</li>
     *   <li>a field of a related record is written by one column: {@code employeeProfileId.idType} beside
     *       {@code employeeProfileId.idType.name} both write the sub-record's {@code idType}, and one would
     *       silently win — as {@code deptId.code} listed twice would.</li>
     * </ul>
     *
     * @param fieldNames the columns' field paths; a blank one is left to {@link #problem}
     * @throws IllegalArgumentException naming the first conflict
     */
    public static void checkColumns(Collection<String> fieldNames) {
        Set<String> direct = new HashSet<>();
        Map<String, List<String>> lookupsByRelation = new LinkedHashMap<>();
        for (String fieldName : fieldNames) {
            if (StringUtils.isBlank(fieldName)) {
                continue;
            }
            int dot = fieldName.indexOf('.');
            if (dot < 0) {
                direct.add(fieldName);
            } else {
                lookupsByRelation.computeIfAbsent(fieldName.substring(0, dot), relation -> new ArrayList<>())
                        .add(fieldName);
            }
        }
        for (Map.Entry<String, List<String>> entry : lookupsByRelation.entrySet()) {
            String relation = entry.getKey();
            if (direct.contains(relation)) {
                throw new IllegalArgumentException(
                        "Import field `{0}` and `{1}` cannot coexist. " +
                                "Either import the relation value directly or use relation lookup, not both.",
                        relation, entry.getValue());
            }
            Set<String> written = new HashSet<>();
            for (String path : entry.getValue()) {
                String[] segments = path.split("\\.");
                if (segments.length > 1 && !written.add(segments[1])) {
                    throw new IllegalArgumentException(
                            "Import fields address `{0}.{1}` more than once; a sub-record field can " +
                                    "be written by only one column.",
                            relation, segments[1]);
                }
            }
        }
    }

    /**
     * The field a column lands on: every segment exists, every one before the last is a relation with a
     * related model, and the last is stored.
     *
     * <p>Unlike a read path, an import lookup supports a to-many relation as its root, such as
     * {@code roleIds.code} and {@code additionalProjectTeamIds.code}.
     *
     * @throws IllegalArgumentException naming what is wrong
     */
    public static MetaField lastField(String modelName, String fullFieldName) {
        String[] fieldsArray = StringUtils.split(fullFieldName, ".");
        Assert.isTrue(fieldsArray != null && fieldsArray.length > 0,
                "Import field `{0}` cannot be empty.", fullFieldName);

        MetaField metaField = null;
        for (int i = 0; i < fieldsArray.length; i++) {
            metaField = ModelManager.getModelField(modelName, fieldsArray[i]);
            if (i < fieldsArray.length - 1) {
                Assert.isTrue(FieldType.RELATED_TYPES.contains(metaField.getFieldType()),
                        "The field {0} in import lookup field {1} must be relation field!",
                        metaField.getFieldName(), fullFieldName);
                Assert.notBlank(metaField.getRelatedModel(),
                        "The field {0} in import lookup field {1} has no related model configured!",
                        metaField.getFieldName(), fullFieldName);
                modelName = metaField.getRelatedModel();
            } else {
                Assert.notTrue(metaField.isDynamic(),
                        "The last field {0} in import field {1} must be a stored field in model {2}!",
                        metaField.getFieldName(), fullFieldName, metaField.getModelName());
            }
        }
        return metaField;
    }

    /**
     * The lookup rules of a dotted column: at most three segments, a root that is a relation, and a third
     * segment only through the row's own one-to-one sub-record.
     *
     * @return the root relation, which the column looks a row up through
     * @throws IllegalArgumentException naming what is wrong
     */
    public static MetaField checkLookupPath(String modelName, String fieldName) {
        String[] parts = fieldName.split("\\.");
        if (parts.length > 3) {
            throw new IllegalArgumentException(
                    "Import field `{0}` has more than two levels of cascade. " +
                    "Supported forms are deptId.code, and employeeProfileId.idType.name where " +
                    "the first hop is a one-to-one sub-record of the row itself.",
                    fieldName);
        }

        String rootField = parts[0];
        // Validate root field is ManyToOne/OneToOne
        if (!ModelManager.existField(modelName, rootField)) {
            throw new IllegalArgumentException(
                    "Import field `{0}`: root field `{1}` does not exist in model `{2}`.",
                    fieldName, rootField, modelName);
        }
        MetaField rootMetaField = ModelManager.getModelField(modelName, rootField);
        if (!FieldType.RELATED_TYPES.contains(rootMetaField.getFieldType())) {
            throw new IllegalArgumentException(
                    "Import field `{0}`: root field `{1}` must be a relation field, but is `{2}`.",
                    fieldName, rootField, rootMetaField.getFieldType().getType());
        }
        if (parts.length == 3) {
            checkNestedLookupPath(fieldName, rootMetaField, parts);
        }
        return rootMetaField;
    }

    /**
     * A three-segment path is allowed through exactly one shape, and this rejects every other.
     *
     * <p>The first hop must be the row's own one-to-one sub-record: its columns are folded into a
     * nested value object rather than used as a business key, so there is a place for a resolved id
     * to land. Through a many-to-one the two segments already are the business key of some other row,
     * and a third would change what the column means. The middle field must be a many-to-one — that is
     * the thing being named — and the leaf must be a plain field on its target, because the leaf is
     * what the cell carries and a relation cannot be typed into a cell.
     */
    private static void checkNestedLookupPath(String fieldName, MetaField rootMetaField, String[] parts) {
        if (!FieldType.ONE_TO_ONE.equals(rootMetaField.getFieldType())) {
            throw new IllegalArgumentException(
                    "Import field `{0}`: a two-level path is only supported through a one-to-one " +
                            "sub-record of the row itself, but `{1}` is `{2}`.",
                    fieldName, parts[0], rootMetaField.getFieldType().getType());
        }
        String subRecordModel = rootMetaField.getRelatedModel();
        if (!ModelManager.existField(subRecordModel, parts[1])) {
            throw new IllegalArgumentException(
                    "Import field `{0}`: `{1}` does not exist in model `{2}`.",
                    fieldName, parts[1], subRecordModel);
        }
        MetaField nestedMetaField = ModelManager.getModelField(subRecordModel, parts[1]);
        if (!FieldType.MANY_TO_ONE.equals(nestedMetaField.getFieldType())) {
            throw new IllegalArgumentException(
                    "Import field `{0}`: `{1}.{2}` must be a many-to-one relation to be looked up by " +
                            "`{3}`, but is `{4}`.",
                    fieldName, subRecordModel, parts[1], parts[2], nestedMetaField.getFieldType().getType());
        }
        String targetModel = nestedMetaField.getRelatedModel();
        Assert.notBlank(targetModel,
                "Import field `{0}`: `{1}.{2}` has no related model configured.",
                fieldName, subRecordModel, parts[1]);
        if (!ModelManager.existField(targetModel, parts[2])) {
            throw new IllegalArgumentException(
                    "Import field `{0}`: `{1}` does not exist in model `{2}`.",
                    fieldName, parts[2], targetModel);
        }
        if (FieldType.RELATED_TYPES.contains(ModelManager.getModelField(targetModel, parts[2]).getFieldType())) {
            throw new IllegalArgumentException(
                    "Import field `{0}`: `{1}.{2}` is a relation itself and cannot be the value a " +
                            "cell carries.",
                    fieldName, targetModel, parts[2]);
        }
    }
}
