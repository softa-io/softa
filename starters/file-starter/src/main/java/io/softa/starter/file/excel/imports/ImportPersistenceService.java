package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.domain.CreateOrUpdateResult;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.WriteValidationException;
import io.softa.starter.file.dto.ImportDataDTO;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.enums.ImportRule;

@Component
public class ImportPersistenceService {

    @Autowired
    private ModelService<?> modelService;

    /**
     * Persist valid import rows according to the import rule.
     */
    public CreateOrUpdateResult persist(ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO) {
        List<Map<String, Object>> rows = importDataDTO.getRows();
        if (CollectionUtils.isEmpty(rows)) {
            return CreateOrUpdateResult.empty();
        }
        if (!Boolean.TRUE.equals(importTemplateDTO.getSkipException())) {
            return persistByRule(importTemplateDTO, rows);
        }
        // In skipException mode, fallback to row-level persistence so one bad row will not fail the whole import task.
        try {
            return persistByRule(importTemplateDTO, rows);
        } catch (RuntimeException ex) {
            return persistRowByRow(importTemplateDTO, importDataDTO);
        }
    }

    private CreateOrUpdateResult persistByRule(ImportTemplateDTO importTemplateDTO, List<Map<String, Object>> rows) {
        ImportRule importRule = importTemplateDTO.getImportRule();
        if (ImportRule.CREATE_OR_UPDATE.equals(importRule)) {
            return modelService.createOrUpdate(
                    importTemplateDTO.getModelName(), rows, importTemplateDTO.getUniqueConstraints());
        } else if (ImportRule.ONLY_CREATE.equals(importRule)) {
            modelService.createList(importTemplateDTO.getModelName(), rows);
            return CreateOrUpdateResult.allCreated(rows);
        } else if (ImportRule.ONLY_UPDATE.equals(importRule)) {
            return updateOnly(importTemplateDTO, rows);
        }
        return CreateOrUpdateResult.empty();
    }

    /**
     * Update the rows that match stored data, and refuse the rows that do not.
     *
     * <p>This rule used to run the same call as CREATE_OR_UPDATE, so a row matching nothing was
     * quietly inserted — a file of corrections with one mistyped key produced a new record instead
     * of an error, and the mistake surfaced later as a duplicate nobody could account for. "Only
     * update" has to mean that a row with nowhere to land is a failed row.
     *
     * <p>Asked before writing rather than sorted out afterwards: the write is one transaction, and
     * by the time it returns the inserts it should not have made are committed.
     *
     * <p>Thrown rather than collected here: a throw is what the surrounding row-by-row fallback
     * already turns into a failed row with a reason, so one rule does not need a second way of
     * reporting the same thing. The message is written here and names the key VALUES that matched
     * nothing — the one thing the reader can act on. With skipException off a single such row fails
     * the whole file and there is no failed-rows file to point at it, so naming only the key field
     * left someone searching a sheet of hundreds of rows for the one that was mistyped.
     */
    private CreateOrUpdateResult updateOnly(ImportTemplateDTO importTemplateDTO, List<Map<String, Object>> rows) {
        List<String> uniqueConstraints = importTemplateDTO.getUniqueConstraints();
        CreateOrUpdateResult split = modelService.splitByExistence(
                importTemplateDTO.getModelName(), rows, uniqueConstraints);
        if (!split.created().isEmpty()) {
            throw new BusinessException(
                    "No existing record matches {0}, and the import template only updates.",
                    describeKeys(split.created(), uniqueConstraints));
        }
        if (!split.updated().isEmpty()) {
            modelService.updateList(importTemplateDTO.getModelName(), split.updated());
        }
        return split;
    }

    /** The most key values named in one message; past that the reader needs the list, not the toast. */
    private static final int MAX_KEYS_NAMED = 5;

    /**
     * {@code code = E1009; code = E1010 (and 3 more)} — the unmatched rows by their key values.
     *
     * <p>In the row-by-row fallback each batch is one row, so each failed row names its own key.
     */
    static String describeKeys(List<Map<String, Object>> rows, List<String> uniqueConstraints) {
        List<String> named = rows.stream()
                .limit(MAX_KEYS_NAMED)
                .map(row -> uniqueConstraints.stream()
                        .map(key -> key + " = " + row.get(key))
                        .collect(Collectors.joining(", ")))
                .toList();
        int more = rows.size() - named.size();
        String text = String.join("; ", named);
        return more > 0 ? text + " (and " + more + " more)" : text;
    }

    private CreateOrUpdateResult persistRowByRow(ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO) {
        List<Map<String, Object>> created = new ArrayList<>();
        List<Map<String, Object>> updated = new ArrayList<>();
        List<Map<String, Object>> failedRows = importDataDTO.getFailedRows() == null
                ? new ArrayList<>()
                : new ArrayList<>(importDataDTO.getFailedRows());
        Iterator<Map<String, Object>> rowIterator = importDataDTO.getRows().iterator();
        Iterator<Map<String, Object>> originalRowIterator = importDataDTO.getOriginalRows().iterator();
        while (rowIterator.hasNext() && originalRowIterator.hasNext()) {
            Map<String, Object> row = rowIterator.next();
            Map<String, Object> originalRow = originalRowIterator.next();
            try {
                // Accumulated rather than replaced: each row is its own batch here, and the caller
                // is owed the whole import's split, not the last row's.
                CreateOrUpdateResult one = persistByRule(importTemplateDTO, List.of(row));
                created.addAll(one.created());
                updated.addAll(one.updated());
            } catch (RuntimeException ex) {
                originalRow.put(FileConstant.FAILED_REASON, failureReason(importTemplateDTO, row, ex));
                failedRows.add(originalRow);
                rowIterator.remove();
                originalRowIterator.remove();
            }
        }
        importDataDTO.setFailedRows(failedRows);
        return new CreateOrUpdateResult(created, updated);
    }

    /**
     * The row's Failed Reason, naming the sheet's column when the write refused a missing required
     * value.
     *
     * <p>With {@code ignoreEmpty} on, the importer leaves the model's requiredness to the ORM (see
     * {@code ImportHandlerFactory#promoteModelRequired}), so a create with a blank mandatory cell is
     * refused here, as {@code Model field Company:name is a required field and cannot be null!} — a
     * model and field name, on a sheet whose column is "Company Name". It is reworded to the message
     * the importer's own check gives, so the reason reads the same whichever layer caught it. Only a
     * refusal we can be sure is that one is reworded: the field is required on the model and the row
     * carried no value for it. Anything else keeps the ORM's message.
     */
    static String failureReason(ImportTemplateDTO importTemplateDTO, Map<String, Object> row, RuntimeException ex) {
        WriteValidationException writeError = findWriteValidationError(ex);
        if (writeError != null) {
            String modelName = importTemplateDTO.getModelName();
            for (String field : writeError.fieldErrors().keySet()) {
                if (row.get(field) != null
                        || !ModelManager.existField(modelName, field)
                        || !ModelManager.getModelField(modelName, field).isRequired()) {
                    continue;
                }
                ImportFieldDTO column = columnFeeding(importTemplateDTO, field);
                if (column != null) {
                    String header = StringUtils.defaultIfBlank(column.getHeader(), column.getFieldName());
                    return "The field `" + header + "` is required";
                }
            }
        }
        return ex.getMessage();
    }

    private static WriteValidationException findWriteValidationError(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof WriteValidationException writeError) {
                return writeError;
            }
        }
        return null;
    }

    /** The template column that writes {@code field}: the field itself, or a lookup path rooted at it. */
    private static ImportFieldDTO columnFeeding(ImportTemplateDTO importTemplateDTO, String field) {
        if (importTemplateDTO.getImportFields() == null) {
            return null;
        }
        for (ImportFieldDTO column : importTemplateDTO.getImportFields()) {
            String fieldName = column.getFieldName();
            if (field.equals(fieldName) || (fieldName != null && fieldName.startsWith(field + "."))) {
                return column;
            }
        }
        return null;
    }
}
