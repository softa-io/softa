package io.softa.starter.file.controller;

import java.util.List;
import java.util.Set;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.service.ExportTemplateService;

/**
 * ExportTemplateController
 */
@Tag(name = "Export Template")
@RestController
@RequestMapping("/ExportTemplate")
public class ExportTemplateController extends EntityController<ExportTemplateService, ExportTemplate, Long> {

    /**
     * List all export templates of the specified model
     *
     * @param modelName model name
     * @return list of export templates
     */
    @Operation(summary = "listByModel", description = "List all export templates of the specified model")
    @PostMapping(value = "/listByModel")
    public ApiResponse<List<ExportTemplate>> listByModel(@RequestParam String modelName) {
        Set<String> childModels = ModelManager.getChildModels(modelName);
        childModels.add(modelName);
        Filters filters = new Filters().in(ExportTemplate::getModelName, childModels);
        FlexQuery flexQuery = new FlexQuery(filters).expandSubQuery(ExportTemplate::getExportFields);
        List<ExportTemplate> templates = service.searchList(flexQuery);
        return ApiResponse.success(templates);
    }
}