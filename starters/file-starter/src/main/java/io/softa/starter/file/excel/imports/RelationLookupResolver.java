package io.softa.starter.file.excel.imports;

import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.dto.ImportFieldDTO;

/**
 * Resolves dotted-path relation lookup fields (e.g. deptId.code)
 * by looking up the related model's business key and writing back the real FK id.
 *
 * <p>Design semantics:
 * <ul>
 *   <li>{@code deptId} — direct FK id import (no lookup needed)</li>
 *   <li>{@code deptId.code} — use Department.code to reverse-lookup, write back deptId</li>
 * </ul>
 *
 * <p>Rules:
 * <ul>
 *   <li>A fieldName containing a dot whose root field is ManyToOne/OneToOne is treated as a relation lookup field.</li>
 *   <li>Only one level of cascade is supported: {@code deptId.code} is OK, {@code deptId.companyId.code} is NOT.</li>
 *   <li>A direct FK field (e.g. {@code deptId}) and a lookup field (e.g. {@code deptId.code}) must not coexist in the same template.</li>
 * </ul>
 */
@Slf4j
@Component
public class RelationLookupResolver {

    @Autowired
    private ModelService<?> modelService;

    /**
     * Describes a group of dotted-path lookup fields sharing the same root FK field.
     * For example, deptId.code and deptId.name would form one group with rootField="deptId".
     *
     * @param rootField the FK field name in the main model (e.g. "deptId")
     * @param relatedModel the related model name (e.g. "Department")
     * @param lookupFields the field names in the related model (e.g. ["code", "name"])
     * @param dottedPaths the original dotted-path field names (e.g. ["deptId.code", "deptId.name"])
     * @param ignoreEmpty whether to skip when all lookup values are empty;
     *                    if true, the rootField is not written (row.remove semantics);
     *                    if false, the rootField is explicitly set to null.
     */
    public record LookupGroup(String rootField, String relatedModel, List<String> lookupFields,
                       List<String> dottedPaths, boolean ignoreEmpty) {}

    /**
     * Detect, validate and return the lookup groups from the import field list.
     *
     * @param modelName the target model name
     * @param importFields the list of import field DTOs
     * @return a list of LookupGroup describing the relation lookups
     */
    public List<LookupGroup> detectLookupGroups(String modelName, List<ImportFieldDTO> importFields) {
        // Collect all fieldNames
        Set<String> directFields = new HashSet<>();
        // rootField -> list of ImportFieldDTOs with dotted paths
        Map<String, List<ImportFieldDTO>> rootToDottedFields = new LinkedHashMap<>();

        for (ImportFieldDTO field : importFields) {
            String fieldName = field.getFieldName();
            if (!fieldName.contains(".")) {
                directFields.add(fieldName);
                continue;
            }
            // Has dot: validate relation lookup
            String[] parts = fieldName.split("\\.");
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "Import field `{0}` has more than one level of cascade. " +
                        "Only single-level relation lookup is supported (e.g. deptId.code). " +
                        "For deeper cascades, consider using a cascaded field.",
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
            if (!FieldType.TO_ONE_TYPES.contains(rootMetaField.getFieldType())) {
                throw new IllegalArgumentException(
                        "Import field `{0}`: root field `{1}` must be ManyToOne or OneToOne, but is `{2}`.",
                        fieldName, rootField, rootMetaField.getFieldType());
            }
            rootToDottedFields.computeIfAbsent(rootField, k -> new ArrayList<>()).add(field);
        }

        // Validate: direct FK field and dotted lookup field must not coexist
        for (String rootField : rootToDottedFields.keySet()) {
            if (directFields.contains(rootField)) {
                List<String> paths = rootToDottedFields.get(rootField).stream()
                        .map(ImportFieldDTO::getFieldName).toList();
                throw new IllegalArgumentException(
                        "Import field `{0}` and `{1}` cannot coexist. " +
                        "Either import the FK id directly or use relation lookup, not both.",
                        rootField, paths);
            }
        }

        // Build lookup groups
        List<LookupGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<ImportFieldDTO>> entry : rootToDottedFields.entrySet()) {
            String rootField = entry.getKey();
            List<ImportFieldDTO> fieldDTOs = entry.getValue();
            MetaField rootMetaField = ModelManager.getModelField(modelName, rootField);
            String relatedModel = rootMetaField.getRelatedModel();
            List<String> dottedPaths = fieldDTOs.stream().map(ImportFieldDTO::getFieldName).toList();
            Assert.notBlank(relatedModel,
                    "Import field `{0}`: root field `{1}` has no related model configured.",
                    dottedPaths, rootField);
            // Extract the lookup field names in the related model (e.g. "code" from "deptId.code")
            List<String> lookupFields = dottedPaths.stream()
                    .map(path -> path.substring(rootField.length() + 1))
                    .toList();
            // Derive ignoreEmpty from the first dotted-path field (all share the same template-level setting)
            boolean ignoreEmpty = Boolean.TRUE.equals(fieldDTOs.get(0).getIgnoreEmpty());
            groups.add(new LookupGroup(rootField, relatedModel, lookupFields, dottedPaths, ignoreEmpty));
        }
        return groups;
    }

    /**
     * Resolve all relation lookup fields in the rows: for each lookup group,
     * batch-query the related model, write back the real FK id, and remove the dotted-path temporary columns.
     *
     * @param rows the import data rows
     * @param lookupGroups the lookup groups detected by {@link #detectLookupGroups}
     * @param skipException when false, throw ValidationException on lookup failure instead of marking FAILED_REASON
     */
    public void resolveRows(List<Map<String, Object>> rows, List<LookupGroup> lookupGroups, boolean skipException) {
        for (LookupGroup group : lookupGroups) {
            resolveGroup(rows, group, skipException);
        }
    }

    /**
     * Resolve one lookup group across all rows.
     */
    private void resolveGroup(List<Map<String, Object>> rows, LookupGroup group, boolean skipException) {
        // Step 1: Collect distinct business key value lists from all rows
        Set<List<Object>> distinctKeys = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            // Skip rows already marked as failed
            if (row.containsKey(FileConstant.FAILED_REASON)) {
                continue;
            }
            List<Object> keyValues = extractKeyValues(row, group);
            if (keyValues != null) {
                distinctKeys.add(keyValues);
            }
        }

        if (distinctKeys.isEmpty()) {
            // All values are empty/null — handle empty rootField and clean up dotted paths
            handleEmptyAndCleanup(rows, group);
            return;
        }

        // Step 2: Batch query related model to get businessKey -> id mapping
        Map<List<Object>, ?> keyToIdMap = modelService.getIdsByBusinessKeys(
                group.relatedModel(), group.lookupFields(), distinctKeys);

        // Step 3: Write back the FK id and remove dotted-path columns
        for (Map<String, Object> row : rows) {
            if (row.containsKey(FileConstant.FAILED_REASON)) {
                removeDottedPaths(row, group);
                continue;
            }
            List<Object> keyValues = extractKeyValues(row, group);
            if (keyValues == null) {
                // All lookup fields are empty — handle based on ignoreEmpty setting
                handleEmptyRootField(row, group);
                removeDottedPaths(row, group);
                continue;
            }
            Object resolvedId = keyToIdMap.get(keyValues);
            if (resolvedId == null) {
                String message = buildNotFoundMessage(group, keyValues);
                if (!skipException) {
                    throw new ValidationException(message);
                }
                String failedReason = row.containsKey(FileConstant.FAILED_REASON)
                        ? row.get(FileConstant.FAILED_REASON) + "; " : "";
                failedReason += message;
                row.put(FileConstant.FAILED_REASON, failedReason);
            } else {
                row.put(group.rootField(), resolvedId);
            }
            removeDottedPaths(row, group);
        }
    }

    /**
     * Handle the empty rootField based on ignoreEmpty setting.
     * <ul>
     *   <li>ignoreEmpty = true: do not write rootField (skip), consistent with BaseImportHandler.row.remove() semantics.</li>
     *   <li>ignoreEmpty = false: explicitly write rootField = null, so downstream persistence can clear existing FK values.</li>
     * </ul>
     */
    private void handleEmptyRootField(Map<String, Object> row, LookupGroup group) {
        if (!group.ignoreEmpty()) {
            row.put(group.rootField(), null);
        }
    }

    /**
     * Handle all-empty fast path: set empty rootField and clean up dotted paths for all non-failed rows.
     */
    private void handleEmptyAndCleanup(List<Map<String, Object>> rows, LookupGroup group) {
        for (Map<String, Object> row : rows) {
            if (!row.containsKey(FileConstant.FAILED_REASON)) {
                handleEmptyRootField(row, group);
            }
            removeDottedPaths(row, group);
        }
    }

    /**
     * Extract the business key values from a row for the given lookup group.
     *
     * @return the key value list, or null if all values are empty
     */
    private List<Object> extractKeyValues(Map<String, Object> row, LookupGroup group) {
        List<Object> values = new ArrayList<>(group.dottedPaths().size());
        boolean allEmpty = true;
        for (String dottedPath : group.dottedPaths()) {
            Object val = row.get(dottedPath);
            if (val != null && (!(val instanceof String s) || !s.isBlank())) {
                allEmpty = false;
            }
            values.add(val);
        }
        return allEmpty ? null : values;
    }

    /**
     * Remove dotted-path temporary columns from a row.
     */
    private void removeDottedPaths(Map<String, Object> row, LookupGroup group) {
        for (String dottedPath : group.dottedPaths()) {
            row.remove(dottedPath);
        }
    }

    private String buildNotFoundMessage(LookupGroup group, List<Object> keyValues) {
        StringBuilder sb = new StringBuilder();
        sb.append("Cannot find ").append(group.relatedModel()).append(" by ");
        for (int i = 0; i < group.lookupFields().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(group.lookupFields().get(i)).append("=").append(keyValues.get(i));
        }
        return sb.toString();
    }
}
