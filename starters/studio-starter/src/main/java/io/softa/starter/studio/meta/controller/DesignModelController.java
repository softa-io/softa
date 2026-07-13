package io.softa.starter.studio.meta.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.meta.service.DesignModelService;

/**
 * DesignModel Model Controller
 */
@Tag(name = "DesignModel")
@RestController
@RequestMapping("/DesignModel")
public class DesignModelController extends AbstractDesignWriteController<DesignModelService, DesignModel> {

    @Override
    protected String modelName() {
        return "DesignModel";
    }

    @Override
    protected String renameKeyField() {
        return "modelName";
    }

    /**
     * Preview the DDL SQL of model, including table creation and index creation
     *
     * @param id Model ID
     * @return Model DDL SQL
     */
    @Operation(description = "Preview the DDL SQL of model, including table creation and index creation")
    @GetMapping(value = "/previewDDL")
    @Parameter(name = "id", description = "Model ID")
    public ApiResponse<String> previewDDL(@RequestParam Long id) {
        return ApiResponse.success(service.previewDDL(id));
    }
}
