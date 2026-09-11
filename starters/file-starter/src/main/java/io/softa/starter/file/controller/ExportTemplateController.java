package io.softa.starter.file.controller;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.support.TemplateScope;
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
        Set<String> modelNames = TemplateScope.of(modelName, this::standaloneModelNames);
        Filters filters = new Filters().in(ExportTemplate::getModelName, modelNames);
        FlexQuery flexQuery = new FlexQuery(filters).expandSubQuery(ExportTemplate::getExportFields);
        List<ExportTemplate> templates = service.searchList(flexQuery);
        return ApiResponse.success(templates);
    }

    /** Models whose export templates belong only on their own page — see {@link TemplateScope}. */
    private Set<String> standaloneModelNames() {
        Filters standalone = new Filters().eq(ExportTemplate::getStandalone, true);
        return service.searchList(new FlexQuery(standalone)).stream()
                .map(ExportTemplate::getModelName)
                .collect(Collectors.toSet());
    }
}
