package io.softa.starter.studio.release.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.studio.release.entity.DesignApp;
import io.softa.starter.studio.release.enums.DesignAppStatus;
import io.softa.starter.studio.release.service.DesignAppService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DesignApp Model Controller
 */
@Tag(name = "DesignApp")
@RestController
@RequestMapping("/DesignApp")
public class DesignAppController extends EntityController<DesignAppService, DesignApp, Long> {

    /**
     * Activate the app.
     *
     * @param id app ID
     * @return true / Exception
     */
    @Operation(description = "Activate the App.")
    @PostMapping(value = "/activate")
    @Parameter(name = "id", description = "App ID")
    public ApiResponse<Boolean> activate(Long id) {
        return transitionStatus(id, DesignAppStatus.ACTIVE);
    }

    /**
     * Put the app into maintenance mode.
     *
     * @param id app ID
     * @return true / Exception
     */
    @Operation(description = "Put the App into maintenance mode.")
    @PostMapping(value = "/enterMaintenance")
    @Parameter(name = "id", description = "App ID")
    public ApiResponse<Boolean> enterMaintenance(Long id) {
        return transitionStatus(id, DesignAppStatus.MAINTENANCE);
    }

    /**
     * Deprecate the app.
     *
     * @param id app ID
     * @return true / Exception
     */
    @Operation(description = "Deprecate the App.")
    @PostMapping(value = "/deprecate")
    @Parameter(name = "id", description = "App ID")
    public ApiResponse<Boolean> deprecate(Long id) {
        return transitionStatus(id, DesignAppStatus.DEPRECATED);
    }

    private ApiResponse<Boolean> transitionStatus(Long id, DesignAppStatus targetStatus) {
        service.transitionStatus(id, targetStatus);
        return ApiResponse.success(true);
    }

}
