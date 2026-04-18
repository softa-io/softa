package io.softa.starter.file.controller;

import java.util.List;
import java.util.Set;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.service.ImportService;
import io.softa.starter.file.service.ImportTemplateService;

/**
 * ImportTemplateController
 */
@Tag(name = "Import Template")
@RestController
@RequestMapping("/ImportTemplate")
public class ImportTemplateController extends EntityController<ImportTemplateService, ImportTemplate, Long> {

    @Autowired
    private ImportService importService;

    /**
     * List all import templates of the specified model
     *
     * @param modelName model name
     * @return list of import templates
     */
    @Operation(summary="listByModel", description = "List all import templates of the specified model")
    @PostMapping(value = "/listByModel")
    public ApiResponse<List<ImportTemplate>> listByModel(@RequestParam String modelName) {
        Set<String> childModels = ModelManager.getChildModels(modelName);
        childModels.add(modelName);
        Filters filters = new Filters().in(ExportTemplate::getModelName, childModels);
        FlexQuery flexQuery = new FlexQuery(filters).expandSubQuery(ImportTemplate::getImportFields);
        List<ImportTemplate> templates = service.searchList(flexQuery);
        return ApiResponse.success(templates);
    }

    /**
     * Get the fileInfo of the import template by template ID.
     * The fileInfo contains the download URL.
     *
     * @param id template ID
     * @return import template fileInfo
     */
    @Operation(description = """
            Get the fileInfo of the import template by template ID.
            The fileInfo contains the download URL.""")
    @GetMapping("/getTemplateFile")
    public ApiResponse<FileInfo> getTemplateFile(@RequestParam(name = "id") Long id) {
        return ApiResponse.success(importService.getTemplateFile(id));
    }

}