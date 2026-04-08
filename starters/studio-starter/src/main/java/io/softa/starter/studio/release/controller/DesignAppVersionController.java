package io.softa.starter.studio.release.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.VersionDeployDTO;
import io.softa.starter.studio.release.entity.DesignAppVersion;
import io.softa.starter.studio.release.service.DesignAppVersionService;

/**
 * DesignAppVersion Model Controller
 */
@Tag(name = "DesignAppVersion")
@RestController
@RequestMapping("/DesignAppVersion")
public class DesignAppVersionController extends EntityController<DesignAppVersionService, DesignAppVersion, Long> {

    /**
     * Deploy the version to an environment.
     *
     * @param versionDeployDTO deployment request
     * @return deployment ID
     */
    @PostMapping(value = "/deployToEnv")
    @Operation(description = "Deploy a sealed/frozen Version to an Environment.")
    public ApiResponse<Long> deployToEnv(@RequestBody VersionDeployDTO versionDeployDTO) {
        return ApiResponse.success(service.deployToEnv(
                versionDeployDTO.getVersionId(),
                versionDeployDTO.getEnvId()));
    }

    /**
     * Seal the version, aggregating WorkItem changes into immutable versionedContent + DDL.
     *
     * @param id Version ID
     * @return true / Exception
     */
    @Operation(description = "Seal the version: aggregate WorkItem changes, generate DDL and diffHash, transition to SEALED.")
    @PostMapping(value = "/sealVersion")
    @Parameter(name = "id", description = "Version ID")
    public ApiResponse<Boolean> sealVersion(@RequestParam Long id) {
        service.sealVersion(id);
        return ApiResponse.success(true);
    }

    /**
     * Freeze the version, marking that it has been deployed.
     *
     * @param id Version ID
     * @return true / Exception
     */
    @Operation(description = "Freeze the version, which means the version has been deployed and cannot be edited anymore.")
    @PostMapping(value = "/freezeVersion")
    @Parameter(name = "id", description = "Version ID")
    public ApiResponse<Boolean> freezeVersion(@RequestParam Long id) {
        service.freezeVersion(id);
        return ApiResponse.success(true);
    }

    /**
     * Unseal a SEALED version back to DRAFT so WorkItems can be added or removed.
     * Not allowed if the version has been deployed.
     *
     * @param id Version ID
     * @return true / Exception
     */
    @Operation(description = "Unseal a SEALED version back to DRAFT. Not allowed if deployed.")
    @PostMapping(value = "/unsealVersion")
    @Parameter(name = "id", description = "Version ID")
    public ApiResponse<Boolean> unsealVersion(@RequestParam Long id) {
        service.unsealVersion(id);
        return ApiResponse.success(true);
    }

    /**
     * Preview the merged content of the version without modifying its status.
     *
     * @param id Version ID
     * @return list of model-level change summaries
     */
    @Operation(description = "Preview the merged content of the version without modifying its status.")
    @GetMapping(value = "/previewVersion")
    @Parameter(name = "id", description = "Version ID")
    public ApiResponse<List<ModelChangesDTO>> previewVersion(@RequestParam Long id) {
        return ApiResponse.success(service.previewVersion(id));
    }

    /**
     * Preview DDL SQL generated from the version's change data.
     * SEALED/FROZEN versions use the stored versionedContent;
     * DRAFT versions aggregate live changes from WorkItems.
     * The returned SQL can be copied to a database client for execution.
     *
     * @param id Version ID
     * @return DDL SQL string
     */
    @Operation(description = "Preview DDL SQL generated from version change data, ready for copy to database client.")
    @GetMapping(value = "/previewDDL")
    @Parameter(name = "id", description = "Version ID")
    public ApiResponse<String> previewDDL(@RequestParam Long id) {
        return ApiResponse.success(service.previewVersionDDL(id));
    }

}
