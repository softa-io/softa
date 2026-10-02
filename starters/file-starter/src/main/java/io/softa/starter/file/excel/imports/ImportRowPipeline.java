package io.softa.starter.file.excel.imports;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.SpringContextUtils;
import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.domain.CreateOrUpdateResult;
import io.softa.starter.file.dto.ImportDataDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.enums.ImportMode;
import io.softa.starter.file.enums.ImportRule;
import io.softa.starter.file.excel.imports.handler.BaseImportHandler;

@Component
public class ImportRowPipeline {

    @Autowired
    private ImportHandlerFactory importHandlerFactory;

    @Autowired
    private RelationLookupResolver relationLookupResolver;

    @Autowired
    private ImportFailureCollector importFailureCollector;

    @Autowired
    private ImportPersistenceService importPersistenceService;

    @Autowired
    private UniqueConstraintValidator uniqueConstraintValidator;

    /**
     * Run the full import row pipeline:
     * 1. Standard field handlers (type conversion, validation, etc.)
     * 2. Relation lookup resolution (dotted-path fields -> FK ids)
     * 3. In-file duplicate suppression (first row with a given unique key wins)
     * 4. Unique constraint pre-check against the database (for ONLY_CREATE rule)
     * 5. Custom handler
     * 6. Failure collection (when skipException=true) or fail-fast (when skipException=false)
     * 7. Persistence
     * 8. Custom handler, again, for whatever needed the rows to exist
     */
    public void importData(ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO) {
        processRows(importTemplateDTO, importDataDTO, ImportMode.IMPORT);
        CreateOrUpdateResult written = importPersistenceService.persist(importTemplateDTO, importDataDTO);
        runAfterPersistence(importTemplateDTO.getCustomHandler(), written, importDataDTO.getEnv());
    }

    /**
     * The custom handler's second pass, after the rows exist.
     *
     * <p>Step 5 runs before persistence, so anything keyed on the new id had nowhere to go. A
     * handler could not defer it either — the only transaction in an import is the one inside the
     * persistence call, which begins after step 5 has returned, so a synchronisation registered
     * there is registered against nothing and silently skipped.
     *
     * <p>Only the rows that were written are handed over, and the two sides are kept apart: the
     * write is the last place that knows which row was inserted and which was matched, and a
     * handler whose work belongs to one of them cannot tell afterwards. A failed row appears in
     * neither list, so a handler is never asked to finish a row that was not written.
     */
    private void runAfterPersistence(String handlerName, CreateOrUpdateResult written, Map<String, Object> env) {
        if (StringUtils.isBlank(handlerName)
                || (CollectionUtils.isEmpty(written.created()) && CollectionUtils.isEmpty(written.updated()))) {
            return;
        }
        CustomImportHandler handler = SpringContextUtils.getBean(handlerName, CustomImportHandler.class);
        handler.afterImportData(written.created(), written.updated(), env);
    }

    /**
     * Run the validation-only pipeline (no persistence).
     * Forces skipException=true so all row errors are collected for complete user feedback.
     *
     * 1. Standard field handlers (type conversion, validation, etc.)
     * 2. Relation lookup resolution (dotted-path fields -> FK ids)
     * 3. In-file duplicate suppression (first row with a given unique key wins)
     * 4. Unique constraint pre-check against the database (for ONLY_CREATE rule)
     * 5. Custom handler
     * 6. Failure collection
     */
    public void validateData(ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO) {
        // Force skipException=true in validation mode to collect all errors
        Boolean originalSkipException = importTemplateDTO.getSkipException();
        try {
            importTemplateDTO.setSkipException(true);
            processRows(importTemplateDTO, importDataDTO, ImportMode.VALIDATE_ONLY);
        } finally {
            importTemplateDTO.setSkipException(originalSkipException);
        }
    }

    /**
     * Common row processing pipeline shared by importData and validateData:
     * 1. Standard field handlers (type conversion, validation, etc.)
     * 2. Relation lookup resolution (dotted-path fields -> FK ids)
     * 3. In-file duplicate suppression (first row with a given unique key wins)
     * 4. Unique constraint pre-check against the database (for ONLY_CREATE rule)
     * 5. Custom handler
     * 6. Failure collection
     *
     * <p>Every step above runs in BOTH modes — the mode reaches only the custom handler, which is the
     * one step that can have side effects the pipeline cannot withhold on its behalf.
     */
    private void processRows(ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO,
                             ImportMode mode) {
        boolean skipException = Boolean.TRUE.equals(importTemplateDTO.getSkipException());
        List<BaseImportHandler> handlers = importHandlerFactory.createHandlers(importTemplateDTO);
        for (BaseImportHandler handler : handlers) {
            handler.handleRows(importDataDTO.getRows(), skipException);
        }
        // Resolve relation lookup fields (e.g. deptId.code -> deptId)
        List<RelationLookupResolver.LookupGroup> lookupGroups =
                relationLookupResolver.detectLookupGroups(importTemplateDTO.getModelName(), importTemplateDTO.getImportFields());
        if (!lookupGroups.isEmpty()) {
            relationLookupResolver.resolveRows(importTemplateDTO.getModelName(), importDataDTO.getRows(),
                    lookupGroups, skipException);
        }
        // Keep only the first of any rows in this file sharing a unique key. Before the database
        // check, so that only sees first occurrences; after the lookup resolution, because a unique
        // key can be a foreign key that step is what produces. Runs for every rule — any of them can
        // be handed the same row twice.
        uniqueConstraintValidator.markInFileDuplicates(importTemplateDTO.getUniqueConstraints(),
                importDataDTO.getRows(), skipException);
        // Pre-check unique constraints against the database for ONLY_CREATE rule
        if (ImportRule.ONLY_CREATE.equals(importTemplateDTO.getImportRule())) {
            uniqueConstraintValidator.validate(importTemplateDTO.getModelName(),
                    importTemplateDTO.getUniqueConstraints(), importDataDTO.getRows(), skipException);
        }
        executeCustomHandler(importTemplateDTO.getCustomHandler(), importDataDTO, mode);
        importFailureCollector.collect(importDataDTO);
    }

    private void executeCustomHandler(String handlerName, ImportDataDTO importDataDTO, ImportMode mode) {
        if (StringUtils.isBlank(handlerName)) {
            return;
        }
        if (!StringTools.isBeanName(handlerName)) {
            throw new IllegalArgumentException("The name of custom import handler `{0}` is invalid.", handlerName);
        }
        try {
            CustomImportHandler handler = SpringContextUtils.getBean(handlerName, CustomImportHandler.class);
            List<Map<String, Object>> rows = importDataDTO.getRows();
            int originalSize = rows.size();
            List<Integer> rowIdentitySnapshot = rows.stream().map(System::identityHashCode).toList();
            handler.handleImportData(rows, importDataDTO.getEnv(), mode.isValidateOnly());
            validateCustomHandlerContract(handlerName, rows, originalSize, rowIdentitySnapshot);
        } catch (NoSuchBeanDefinitionException e) {
            throw new IllegalArgumentException("The custom import handler `{0}` is not found.", handlerName);
        }
    }

    void validateCustomHandlerContract(String handlerName, List<Map<String, Object>> rows, int originalSize,
                                       List<Integer> rowIdentitySnapshot) {
        if (rows.size() != originalSize) {
            throw new IllegalArgumentException(
                    "The custom import handler `{0}` must not add or remove rows.", handlerName);
        }
        for (int i = 0; i < rows.size(); i++) {
            if (System.identityHashCode(rows.get(i)) != rowIdentitySnapshot.get(i)) {
                throw new IllegalArgumentException(
                        "The custom import handler `{0}` must not reorder or replace row objects.", handlerName);
            }
        }
    }
}
