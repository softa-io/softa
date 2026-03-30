package io.softa.starter.file.excel.imports;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.SpringContextUtils;
import io.softa.framework.base.utils.StringTools;
import io.softa.starter.file.dto.ImportDataDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
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
     * 3. Custom handler
     * 4. Failure collection (when skipException=true) or fail-fast (when skipException=false)
     * 5. Persistence
     */
    public void importData(ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO) {
        processRows(importTemplateDTO, importDataDTO);
        importPersistenceService.persist(importTemplateDTO, importDataDTO.getRows());
    }

    /**
     * Run the validation-only pipeline (no persistence).
     * Forces skipException=true so all row errors are collected for complete user feedback.
     *
     * 1. Standard field handlers (type conversion, validation, etc.)
     * 2. Relation lookup resolution (dotted-path fields -> FK ids)
     * 3. Unique constraint pre-check against the database (for ONLY_CREATE rule)
     * 4. Custom handler
     * 5. Failure collection
     */
    public void validateData(ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO) {
        // Force skipException=true in validation mode to collect all errors
        Boolean originalSkipException = importTemplateDTO.getSkipException();
        try {
            importTemplateDTO.setSkipException(true);
            processRows(importTemplateDTO, importDataDTO);
        } finally {
            importTemplateDTO.setSkipException(originalSkipException);
        }
    }

    /**
     * Common row processing pipeline shared by importData and validateData:
     * 1. Standard field handlers (type conversion, validation, etc.)
     * 2. Relation lookup resolution (dotted-path fields -> FK ids)
     * 3. Unique constraint pre-check against the database (for ONLY_CREATE rule)
     * 4. Custom handler
     * 5. Failure collection
     */
    private void processRows(ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO) {
        boolean skipException = Boolean.TRUE.equals(importTemplateDTO.getSkipException());
        List<BaseImportHandler> handlers = importHandlerFactory.createHandlers(importTemplateDTO);
        for (BaseImportHandler handler : handlers) {
            handler.handleRows(importDataDTO.getRows(), skipException);
        }
        // Resolve relation lookup fields (e.g. deptId.code -> deptId)
        List<RelationLookupResolver.LookupGroup> lookupGroups =
                relationLookupResolver.detectLookupGroups(importTemplateDTO.getModelName(), importTemplateDTO.getImportFields());
        if (!lookupGroups.isEmpty()) {
            relationLookupResolver.resolveRows(importDataDTO.getRows(), lookupGroups, skipException);
        }
        // Pre-check unique constraints against the database for ONLY_CREATE rule
        if (ImportRule.ONLY_CREATE.equals(importTemplateDTO.getImportRule())) {
            uniqueConstraintValidator.validate(importTemplateDTO.getModelName(),
                    importTemplateDTO.getUniqueConstraints(), importDataDTO.getRows(), skipException);
        }
        executeCustomHandler(importTemplateDTO.getCustomHandler(), importDataDTO);
        importFailureCollector.collect(importDataDTO);
    }

    private void executeCustomHandler(String handlerName, ImportDataDTO importDataDTO) {
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
            handler.handleImportData(rows, importDataDTO.getEnv());
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
