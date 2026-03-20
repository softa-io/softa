package io.softa.starter.file.excel.imports;

import java.util.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;

/**
 * Validates unique constraints for import rows against the database.
 *
 * <p>This validator is used in the ONLY_CREATE import rule to pre-check whether any of the imported rows
 * would violate unique constraints (i.e., duplicate records already exist in the database).
 *
 * <p>For each row, a query is built using the unique constraint fields to check existence in the database.
 * If a matching record is found, the row is marked with a {@link FileConstant#FAILED_REASON}.
 */
@Slf4j
@Component
public class UniqueConstraintValidator {

    @Autowired
    private ModelService<?> modelService;

    /**
     * Validate unique constraints for the given rows against the database.
     * For each row, check if a record with the same unique constraint field values already exists.
     * If it exists, either mark the row with FAILED_REASON (when skipException=true)
     * or throw a ValidationException (when skipException=false).
     *
     * @param modelName the model name
     * @param uniqueConstraints the list of unique constraint field names
     * @param rows the import data rows
     * @param skipException whether to skip exceptions and mark rows as failed
     */
    public void validate(String modelName, List<String> uniqueConstraints,
                         List<Map<String, Object>> rows, boolean skipException) {
        if (CollectionUtils.isEmpty(uniqueConstraints) || CollectionUtils.isEmpty(rows)) {
            return;
        }
        // Collect distinct key combinations from all rows
        Map<List<Object>, List<Map<String, Object>>> keyToRows = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.containsKey(FileConstant.FAILED_REASON)) {
                continue;
            }
            List<Object> keyValues = extractKeyValues(row, uniqueConstraints);
            if (keyValues == null) {
                // If any unique constraint field is null/empty, skip this row
                continue;
            }
            keyToRows.computeIfAbsent(keyValues, _ -> new ArrayList<>()).add(row);
        }
        if (keyToRows.isEmpty()) {
            return;
        }
        // Batch query the database using unique constraint fields
        Set<List<Object>> existingKeys = batchCheckExistence(modelName, uniqueConstraints, keyToRows.keySet());
        // Mark rows whose unique key already exists in the database
        for (List<Object> existingKey : existingKeys) {
            List<Map<String, Object>> duplicateRows = keyToRows.get(existingKey);
            if (duplicateRows == null) {
                continue;
            }
            String message = buildDuplicateMessage(uniqueConstraints, existingKey);
            for (Map<String, Object> row : duplicateRows) {
                if (!skipException) {
                    throw new ValidationException(message);
                }
                String failedReason = row.containsKey(FileConstant.FAILED_REASON)
                        ? row.get(FileConstant.FAILED_REASON) + "; " : "";
                failedReason += message;
                row.put(FileConstant.FAILED_REASON, failedReason);
            }
        }
    }

    /**
     * Extract the unique constraint field values from a row.
     *
     * @param row the row data
     * @param uniqueConstraints the unique constraint field names
     * @return a list of field values, or null if any field value is null/empty
     */
    private List<Object> extractKeyValues(Map<String, Object> row, List<String> uniqueConstraints) {
        List<Object> values = new ArrayList<>(uniqueConstraints.size());
        for (String field : uniqueConstraints) {
            Object value = row.get(field);
            if (value == null || (value instanceof String s && s.isBlank())) {
                return null;
            }
            values.add(value);
        }
        return values;
    }

    /**
     * Batch check existence in the database for the given key combinations.
     * Uses IN query for single-field unique constraint, tupleIn for multi-field.
     *
     * @param modelName the model name
     * @param uniqueConstraints the unique constraint field names
     * @param keyCombinations the distinct key combinations to check
     * @return the set of key combinations that already exist in the database
     */
    private Set<List<Object>> batchCheckExistence(String modelName, List<String> uniqueConstraints,
                                                   Set<List<Object>> keyCombinations) {
        Set<List<Object>> existingKeys = new LinkedHashSet<>();
        if (uniqueConstraints.size() == 1) {
            // Single field: use IN query
            String field = uniqueConstraints.getFirst();
            List<Object> allValues = keyCombinations.stream()
                    .map(List::getFirst)
                    .toList();
            Filters filters = new Filters().in(field, allValues);
            List<String> fields = List.of(field);
            List<Map<String, Object>> existingRows = modelService.searchList(modelName,
                    new FlexQuery(fields, filters, null));
            for (Map<String, Object> existingRow : existingRows) {
                Object value = existingRow.get(field);
                if (value != null) {
                    existingKeys.add(List.of(value));
                }
            }
        } else {
            // Multiple fields: use tupleIn query
            List<List<Object>> tupleValues = new ArrayList<>(keyCombinations);
            Filters filters = new Filters().tupleIn(uniqueConstraints, tupleValues);
            List<Map<String, Object>> existingRows = modelService.searchList(modelName,
                    new FlexQuery(new ArrayList<>(uniqueConstraints), filters, null));
            for (Map<String, Object> existingRow : existingRows) {
                List<Object> key = new ArrayList<>(uniqueConstraints.size());
                for (String field : uniqueConstraints) {
                    key.add(existingRow.get(field));
                }
                existingKeys.add(key);
            }
        }
        return existingKeys;
    }

    /**
     * Build a user-friendly message for duplicate records.
     */
    private String buildDuplicateMessage(List<String> uniqueConstraints, List<Object> keyValues) {
        StringBuilder sb = new StringBuilder("Duplicate record already exists: ");
        for (int i = 0; i < uniqueConstraints.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(uniqueConstraints.get(i)).append("=").append(keyValues.get(i));
        }
        return sb.toString();
    }
}



