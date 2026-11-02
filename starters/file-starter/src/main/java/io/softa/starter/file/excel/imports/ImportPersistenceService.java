package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.CreateOrUpdateResult;
import io.softa.framework.orm.service.ImportScope;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.WriteValidationException;
import io.softa.starter.file.dto.ImportDataDTO;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.enums.ImportRule;

@Slf4j
@Component
public class ImportPersistenceService {

    @Autowired
    private ModelService<?> modelService;

    /** Absent in an application with no transaction manager; a fail-fast file then writes layer by layer. */
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    /**
     * Persist valid import rows according to the import rule.
     *
     * <p>When rows name other rows of the file ({@link ImportDataDTO#getInFileReferences()}), the file is
     * written in layers — see {@link #persistInLayers}. Otherwise in one go, as always.
     */
    public CreateOrUpdateResult persist(ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO) {
        List<Map<String, Object>> rows = importDataDTO.getRows();
        if (CollectionUtils.isEmpty(rows)) {
            return CreateOrUpdateResult.empty();
        }
        List<InFileReferences.Reference> references = referencesAmong(rows, importDataDTO.getInFileReferences());
        if (!references.isEmpty()) {
            return persistInLayers(importTemplateDTO, importDataDTO, references);
        }
        return persistFlat(importTemplateDTO, importDataDTO);
    }

    /** The references whose row is still to be written; the rows that failed have left {@code rows}. */
    private static List<InFileReferences.Reference> referencesAmong(List<Map<String, Object>> rows,
                                                                  List<InFileReferences.Reference> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        Set<Map<String, Object>> live = Collections.newSetFromMap(new IdentityHashMap<>());
        live.addAll(rows);
        return references.stream().filter(reference -> live.contains(reference.row())).toList();
    }

    /**
     * Write the rows layer by layer, each after the rows it names.
     *
     * <p>A layer's references are filled from the ids the earlier layers were written under, matched on
     * the values the reference names its target by — the row objects themselves cannot be followed, since
     * a failed batch is retried from copies. A row whose target did not make it in is not written and
     * fails with that reason; the rows naming it follow at the next layer.
     *
     * <p>A fail-fast file stays all or nothing: every layer goes in one transaction, so a later layer
     * failing takes the earlier ones back out. A file that fails row by row commits each layer as it goes,
     * as it already committed each row.
     */
    private CreateOrUpdateResult persistInLayers(ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO,
                                                 List<InFileReferences.Reference> references) {
        boolean failFast = !Boolean.TRUE.equals(importTemplateDTO.getSkipException());
        if (failFast && transactionManager != null) {
            return new TransactionTemplate(transactionManager)
                    .execute(ignored -> writeLayers(importTemplateDTO, importDataDTO, references, true));
        }
        if (failFast) {
            log.warn("No transaction manager: the fail-fast import of {} is written layer by layer, and a "
                    + "failure in a later layer leaves the earlier layers committed.", importTemplateDTO.getModelName());
        }
        return writeLayers(importTemplateDTO, importDataDTO, references, failFast);
    }

    private CreateOrUpdateResult writeLayers(ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO,
                                             List<InFileReferences.Reference> references, boolean failFast) {
        List<Map<String, Object>> rows = importDataDTO.getRows();
        List<Map<String, Object>> originalRows = importDataDTO.getOriginalRows();
        Map<Map<String, Object>, List<InFileReferences.Reference>> byRow = new IdentityHashMap<>();
        for (InFileReferences.Reference reference : references) {
            byRow.computeIfAbsent(reference.row(), ignored -> new ArrayList<>()).add(reference);
        }
        // The id each written row went in under, by the values a reference names it with — read back from
        // the written rows, not followed by object: a failed batch is retried from copies. This assumes
        // the write leaves those values as the file gave them, give or take surrounding spaces, which
        // the comparison ignores; a field the write rewrites (an encrypted one) cannot be referenced.
        Map<List<String>, Map<List<Object>, Object>> written = new HashMap<>();
        Set<List<String>> keyFields = references.stream().map(InFileReferences.Reference::lookupFields)
                .collect(Collectors.toSet());

        List<Map<String, Object>> created = new ArrayList<>();
        List<Map<String, Object>> updated = new ArrayList<>();
        List<Map<String, Object>> keptRows = new ArrayList<>();
        List<Map<String, Object>> keptOriginals = new ArrayList<>();
        List<Map<String, Object>> failedRows = importDataDTO.getFailedRows() == null
                ? new ArrayList<>() : new ArrayList<>(importDataDTO.getFailedRows());

        for (List<Integer> layer : InFileReferences.layers(rows, references)) {
            ImportDataDTO layerData = new ImportDataDTO();
            layerData.setEnv(importDataDTO.getEnv());
            layerData.setRows(new ArrayList<>());
            layerData.setOriginalRows(new ArrayList<>());
            for (int i : layer) {
                Map<String, Object> row = rows.get(i);
                Map<String, Object> originalRow = originalRows.get(i);
                String missing = fillReferences(row, byRow.getOrDefault(row, List.of()), written);
                if (missing != null) {
                    if (failFast) {
                        throw new BusinessException(missing);
                    }
                    originalRow.put(FileConstant.FAILED_REASON, missing);
                    failedRows.add(originalRow);
                    continue;
                }
                layerData.getRows().add(row);
                layerData.getOriginalRows().add(originalRow);
            }
            if (layerData.getRows().isEmpty()) {
                continue;
            }
            CreateOrUpdateResult result = failFast
                    ? persistByRule(importTemplateDTO, layerData.getRows())
                    : persistFlat(importTemplateDTO, layerData);
            created.addAll(result.created());
            updated.addAll(result.updated());
            keptRows.addAll(layerData.getRows());
            keptOriginals.addAll(layerData.getOriginalRows());
            if (layerData.getFailedRows() != null) {
                failedRows.addAll(layerData.getFailedRows());
            }
            remember(result.created(), keyFields, written);
            remember(result.updated(), keyFields, written);
        }
        importDataDTO.setRows(keptRows);
        importDataDTO.setOriginalRows(keptOriginals);
        importDataDTO.setFailedRows(failedRows);
        return new CreateOrUpdateResult(created, updated);
    }

    /**
     * Fill a row's references from the rows already written, or say which one is missing.
     *
     * @return null when every reference was filled, else the reason the row cannot be written
     */
    private static String fillReferences(Map<String, Object> row, List<InFileReferences.Reference> references,
                                         Map<List<String>, Map<List<Object>, Object>> written) {
        for (InFileReferences.Reference reference : references) {
            Object id = written.getOrDefault(reference.lookupFields(), Map.of()).get(reference.key());
            if (id == null) {
                return reference.label() + " " + reference.keyText() + " is a row of this file that was not imported.";
            }
            row.put(reference.rootField(), id);
        }
        return null;
    }

    private static void remember(List<Map<String, Object>> rows, Set<List<String>> keyFields,
                                 Map<List<String>, Map<List<Object>, Object>> written) {
        for (Map<String, Object> row : rows) {
            Object id = row.get(ModelConstant.ID);
            if (id == null) {
                continue;
            }
            for (List<String> fields : keyFields) {
                List<Object> key = InFileReferences.ownKey(row, fields);
                if (key != null) {
                    written.computeIfAbsent(fields, ignored -> new HashMap<>()).putIfAbsent(key, id);
                }
            }
        }
    }

    /** One write of every row, falling back to row by row when the file fails rows rather than the whole. */
    private CreateOrUpdateResult persistFlat(ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO) {
        List<Map<String, Object>> rows = importDataDTO.getRows();
        if (!Boolean.TRUE.equals(importTemplateDTO.getSkipException())) {
            return persistByRule(importTemplateDTO, rows);
        }
        // In skipException mode, fallback to row-level persistence so one bad row will not fail the whole import task.
        // The retry starts from the rows as they were read. The write works on the rows in place — a nested
        // one-to-one row is given the id it was inserted under, and its parent row is handed that id in place
        // of the nested value — and the failed batch rolls those inserts back. Retried as left, every row
        // would name a record that no longer exists: refused for a caller held to its row range, and written
        // pointing at nothing for one who is not.
        List<Map<String, Object>> asRead = copyRows(rows);
        try {
            return persistByRule(importTemplateDTO, rows);
        } catch (RuntimeException ex) {
            importDataDTO.setRows(asRead);
            return persistRowByRow(importTemplateDTO, importDataDTO);
        }
    }

    /** A copy deep enough that a write on one leaves the other untouched: every nested map and list is new. */
    static List<Map<String, Object>> copyRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copies = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            copies.add(copyMap(row));
        }
        return copies;
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(source.size());
        source.forEach((key, value) -> copy.put(key, copyValue(value)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyMap((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(copyValue(item));
            }
            return copy;
        }
        return value;
    }

    private CreateOrUpdateResult persistByRule(ImportTemplateDTO importTemplateDTO, List<Map<String, Object>> rows) {
        // Marked as an import's writes, so a refusal speaks to the uploader ("update", not "edit").
        return ImportScope.call(() -> writeByRule(importTemplateDTO, rows));
    }

    private CreateOrUpdateResult writeByRule(ImportTemplateDTO importTemplateDTO, List<Map<String, Object>> rows) {
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
     * refused here, as {@code Company: Company Name is required.} — the model's and the field's labels,
     * which need not be the sheet's column header. It is reworded to the message the importer's own
     * check gives, naming the column, so the reason reads the same whichever layer caught it. Only a
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
