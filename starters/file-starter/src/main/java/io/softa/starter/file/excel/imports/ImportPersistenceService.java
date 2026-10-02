package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.domain.CreateOrUpdateResult;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.dto.ImportDataDTO;
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
     * reporting the same thing. The message is written here and names only the key, which is the
     * one thing the reader can act on.
     */
    private CreateOrUpdateResult updateOnly(ImportTemplateDTO importTemplateDTO, List<Map<String, Object>> rows) {
        List<String> uniqueConstraints = importTemplateDTO.getUniqueConstraints();
        CreateOrUpdateResult split = modelService.splitByExistence(
                importTemplateDTO.getModelName(), rows, uniqueConstraints);
        if (!split.created().isEmpty()) {
            throw new BusinessException(
                    "This row matches no existing record on {0}, and the import template only updates.",
                    String.join(", ", uniqueConstraints));
        }
        if (!split.updated().isEmpty()) {
            modelService.updateList(importTemplateDTO.getModelName(), split.updated());
        }
        return split;
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
                originalRow.put(FileConstant.FAILED_REASON, ex.getMessage());
                failedRows.add(originalRow);
                rowIterator.remove();
                originalRowIterator.remove();
            }
        }
        importDataDTO.setFailedRows(failedRows);
        return new CreateOrUpdateResult(created, updated);
    }
}
