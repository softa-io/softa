package io.softa.starter.studio.release.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.service.DesignAppEnvService;

/**
 * DesignAppEnv Model Controller
 */
@Tag(name = "DesignAppEnv")
@RestController
@RequestMapping("/DesignAppEnv")
public class DesignAppEnvController extends EntityController<DesignAppEnvService, DesignAppEnv, Long> {

    /**
     * Compare the design-time snapshot with the actual runtime metadata for the given environment.
     * Detects drift caused by direct SQL changes, unsynced runtime modifications, etc.
     *
     * @param envId Environment ID
     * @return List of model changes representing the drift between snapshot and runtime
     */
    @Operation(description = "Compare design-time snapshot with runtime metadata for an environment.")
    @GetMapping(value = "/compareDesignWithRuntime")
    @Parameter(name = "envId", description = "Environment ID")
    public ApiResponse<List<ModelChangesDTO>> compareDesignWithRuntime(@RequestParam Long envId) {
        return ApiResponse.success(service.compareDesignWithRuntime(envId));
    }

}
