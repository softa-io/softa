package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.excel.imports.handler.BaseImportHandler;
import io.softa.starter.file.excel.imports.handler.BooleanHandler;
import io.softa.starter.file.excel.imports.handler.DateHandler;
import io.softa.starter.file.excel.imports.handler.DateTimeHandler;
import io.softa.starter.file.excel.imports.handler.DefaultHandler;
import io.softa.starter.file.excel.imports.handler.MultiOptionHandler;
import io.softa.starter.file.excel.imports.handler.OptionHandler;
import io.softa.starter.file.excel.imports.handler.TimeHandler;

@Component
public class ImportHandlerFactory {

    /**
     * Create the field handlers for the import template.
     * Dotted-path relation lookup fields (e.g. deptId.code) are skipped here
     * because they are resolved by {@link RelationLookupResolver}.
     */
    public List<BaseImportHandler> createHandlers(ImportTemplateDTO importTemplateDTO) {
        String modelName = importTemplateDTO.getModelName();
        List<BaseImportHandler> handlers = new ArrayList<>();
        for (ImportFieldDTO importFieldDTO : importTemplateDTO.getImportFields()) {
            String fieldName = importFieldDTO.getFieldName();
            // Skip relation lookup fields — they contain a dot and are handled by RelationLookupResolver
            if (fieldName.contains(".")) {
                continue;
            }
            if (!ModelManager.existField(modelName, fieldName)) {
                continue;
            }
            MetaField metaField = ModelManager.getModelField(modelName, fieldName);
            if (!Boolean.TRUE.equals(importFieldDTO.getRequired())) {
                importFieldDTO.setRequired(metaField.isRequired());
            }
            handlers.add(createHandler(metaField, importFieldDTO));
        }
        return handlers;
    }

    BaseImportHandler createHandler(MetaField metaField, ImportFieldDTO importFieldDTO) {
        return switch (metaField.getFieldType()) {
            case BOOLEAN -> new BooleanHandler(metaField, importFieldDTO);
            case DATE -> new DateHandler(metaField, importFieldDTO);
            case DATE_TIME -> new DateTimeHandler(metaField, importFieldDTO);
            case TIME -> new TimeHandler(metaField, importFieldDTO);
            case MULTI_OPTION -> new MultiOptionHandler(metaField, importFieldDTO);
            case OPTION -> new OptionHandler(metaField, importFieldDTO);
            default -> new DefaultHandler(metaField, importFieldDTO);
        };
    }
}
