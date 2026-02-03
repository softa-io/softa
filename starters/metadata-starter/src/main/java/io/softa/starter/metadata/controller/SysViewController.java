package io.softa.starter.metadata.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.controller.dto.ModelViewDTO;
import io.softa.starter.metadata.entity.SysView;
import io.softa.starter.metadata.service.SysViewService;

/**
 * SysView Model Controller
 */
@Tag(name = "SysView")
@RestController
@RequestMapping("/SysView")
public class SysViewController extends EntityController<SysViewService, SysView, Long> {

    /**
     * Set the default view for current user.
     * @param modelViewDTO model name and view ID
     * @return Boolean
     */
    @Operation(summary = "setDefaultView", description =
            "Set the current user's default view based on the model name and view ID: {\"modelName\": \"Employee\", \"viewId\": 1}")
    @PostMapping(value = "/setDefaultView")
    @SkipPermissionCheck
    public ApiResponse<Boolean> setDefaultView(@RequestBody @Valid ModelViewDTO modelViewDTO) {
        return ApiResponse.success(service.setDefaultView(modelViewDTO.getModel(), modelViewDTO.getViewId()));
    }

    /**
     * Get the views of the specified model, including public views and personal views
     * @param modelName Model name
     * @return List of views
     */
    @Operation(summary = "getModelViews", description =
            "Get the list of views visible to the current user based on the model name. [Public views, personal views] and sorted by sequence.")
    @GetMapping(value = "/getModelViews")
    @Parameters({
            @Parameter(name = "modelName", description = "Model Name", required = true)
    })
    public ApiResponse<List<SysView>> getModelViews(@RequestParam String modelName) {
        Assert.notBlank(modelName, "Model name cannot be empty!");
        return ApiResponse.success(service.getModelViews(modelName));
    }
}