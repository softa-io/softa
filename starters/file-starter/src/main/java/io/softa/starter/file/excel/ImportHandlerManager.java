package io.softa.starter.file.excel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.SpringContextUtils;
import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.dto.ImportDataDTO;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.enums.ImportRule;
import io.softa.starter.file.excel.handler.*;

/**
 * ImportHandlerManager
 */
@Component
public class ImportHandlerManager {

    @Autowired
    private ModelService<?> modelService;

    /**
     * Import data
     *
     * @param importTemplateDTO The import config DTO
     * @param importDataDTO  The import data DTO
     */
    public void importData(ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO) {
        // convert data
        String modelName = importTemplateDTO.getModelName();
        List<BaseImportHandler> handlers = new ArrayList<>();
        for (ImportFieldDTO importFieldDTO : importTemplateDTO.getImportFields()) {
            if (ModelManager.existField(modelName, importFieldDTO.getFieldName())) {
                MetaField metaField = ModelManager.getModelField(modelName, importFieldDTO.getFieldName());
                if (!Boolean.TRUE.equals(importFieldDTO.getRequired())) {
                    importFieldDTO.setRequired(metaField.isRequired());
                }
                BaseImportHandler handler = this.createHandler(metaField, importFieldDTO);
                handlers.add(handler);
            }
        }
        // execute standard handlers
        for (BaseImportHandler handler : handlers) {
            handler.handleRows(importDataDTO.getRows());
        }
        // execute custom handler
        this.executeCustomHandler(importTemplateDTO.getCustomHandler(), importDataDTO);
        // separate failed rows
        this.separateFailedRows(importDataDTO);
        // persist to database
        this.persistToDatabase(importTemplateDTO, importDataDTO.getRows());
    }

    /**
     * Create the import data handler
     *
     * @param metaField The meta field
     * @return The handler
     */
    private BaseImportHandler createHandler(MetaField metaField, ImportFieldDTO importFieldDTO) {
        return switch (metaField.getFieldType()) {
            case BOOLEAN -> new BooleanHandler(metaField, importFieldDTO);
            case DATE -> new DateHandler(metaField, importFieldDTO);
            case DATE_TIME -> new DateTimeHandler(metaField, importFieldDTO);
            case MULTI_OPTION -> new MultiOptionHandler(metaField, importFieldDTO);
            case OPTION -> new OptionHandler(metaField, importFieldDTO);
            default -> new DefaultHandler(metaField, importFieldDTO);
        };
    }

    /**
     * Execute the custom handler
     *
     * @param handlerName The handler name
     * @param importDataDTO The import data DTO
     */
    private void executeCustomHandler(String handlerName, ImportDataDTO importDataDTO) {
        if (StringUtils.isNotBlank(handlerName)) {
            if (!StringTools.isBeanName(handlerName)) {
                throw new IllegalArgumentException("The name of custom import handler `{0}` is invalid.", handlerName);
            }
            try {
                CustomImportHandler handler = SpringContextUtils.getBean(handlerName, CustomImportHandler.class);
                handler.handleImportData(importDataDTO.getRows(), importDataDTO.getEnv());
            } catch (NoSuchBeanDefinitionException e) {
                throw new IllegalArgumentException("The custom import handler `{0}` is not found.", handlerName);
            }
        }
    }

    /**
     * Separates failed rows from the 'rows' field in the ImportDataDTO object
     * and moves them to the 'failedRows' field based on the presence of the key 'Failed Reason'.
     *
     * @param importDataDTO The ImportDataDTO object to process
     */
    public void separateFailedRows(ImportDataDTO importDataDTO) {
        List<Map<String, Object>> failedRows = new ArrayList<>();
        // Use an iterator to traverse the list to avoid ConcurrentModificationException
        Iterator<Map<String, Object>> rowIterator = importDataDTO.getRows().iterator();
        Iterator<Map<String, Object>> originalRowIterator = importDataDTO.getOriginalRows().iterator();
        // Traverse the rows and originalRows list simultaneously
        while (rowIterator.hasNext() && originalRowIterator.hasNext()) {
            Map<String, Object> row = rowIterator.next();
            Map<String, Object> originalRow = originalRowIterator.next();
            if (row.containsKey(FileConstant.FAILED_REASON)) {
                originalRow.put(FileConstant.FAILED_REASON, row.get(FileConstant.FAILED_REASON));
                // Remove row containing "Failed Reason" from rows and add them to failedRows
                rowIterator.remove();
                originalRowIterator.remove();
                // Add the original row to the failed rows
                failedRows.add(originalRow);
            }
        }
        importDataDTO.setFailedRows(failedRows);
    }

    /**
     * Create or update the data by the import rule
     *
     * @param importTemplateDTO The importTemplateDTO object
     * @param rows       The rows
     */
    private void persistToDatabase(ImportTemplateDTO importTemplateDTO, List<Map<String, Object>> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return;
        }
        ImportRule importRule = importTemplateDTO.getImportRule();
        if (ImportRule.CREATE_OR_UPDATE.equals(importRule)) {
            modelService.createOrUpdate(importTemplateDTO.getModelName(), rows, importTemplateDTO.getUniqueConstraints());
        } else if (ImportRule.ONLY_CREATE.equals(importRule)) {
            modelService.createList(importTemplateDTO.getModelName(), rows);
        } else if (ImportRule.ONLY_UPDATE.equals(importRule)) {
            // TODO: remove ONLY_UPDATE import rule
            modelService.createOrUpdate(importTemplateDTO.getModelName(), rows, importTemplateDTO.getUniqueConstraints());
        }
    }
}
