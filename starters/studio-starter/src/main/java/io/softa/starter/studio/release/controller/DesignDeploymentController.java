package io.softa.starter.studio.release.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.studio.release.entity.DesignDeployment;
import io.softa.starter.studio.release.service.DesignDeploymentService;

/**
 * DesignDeployment Model Controller.
 * <p>
 * Deployment resource controller. Exposes deployment-centric operations such as retry and preview.
 */
@Tag(name = "DesignDeployment")
@RestController
@RequestMapping("/DesignDeployment")
public class DesignDeploymentController extends EntityController<DesignDeploymentService, DesignDeployment, Long> {

    /**
     * Retry a failed deployment.
     */
    @Operation(description = "Retry a failed deployment by creating a new Deployment with the same parameters.")
    @PostMapping(value = "/retry")
    @Parameter(name = "id", description = "Deployment ID")
    public ApiResponse<Long> retry(@RequestParam Long id) {
        return ApiResponse.success(service.retryDeployment(id));
    }

}
