package io.softa.starter.studio.release.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.studio.release.dto.DriftEnvelopeDTO;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.event.DesignAppEnvDriftRefreshEvent;
import io.softa.starter.studio.release.service.DesignAppEnvService;

/**
 * DesignAppEnv Model Controller
 */
@Tag(name = "DesignAppEnv")
@RestController
@RequestMapping("/DesignAppEnv")
public class DesignAppEnvController extends EntityController<DesignAppEnvService, DesignAppEnv, Long> {

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    /**
     * Compare the design-time snapshot with the actual runtime metadata for the given environment.
     * Detects drift caused by direct SQL changes, unsynced runtime modifications, etc.
     * <p>
     * Returns a drift-oriented view where each row carries {@code expected} (snapshot) and
     * {@code actual} (runtime) sides directly with a {@link io.softa.starter.studio.release.enums.DriftKind}
     * label, instead of the deploy-direction {@code RowChangeDTO} graph used internally.
     *
     * @param id Environment ID
     * @return drift grouped by model
     */
    @Operation(description = "Compare design-time snapshot with runtime metadata for an environment.")
    @GetMapping(value = "/compareDesignWithRuntime")
    @Parameter(name = "id", description = "Environment ID")
    public ApiResponse<DriftEnvelopeDTO> compareDesignWithRuntime(@RequestParam Long id) {
        return ApiResponse.success(service.getDriftEnvelope(id));
    }

    /**
     * Kick off an asynchronous drift recomputation for an env. Returns immediately —
     * the fresh result appears on {@code GET /compareDesignWithRuntime} once the
     * background worker finishes. Safe to spam: concurrent refreshes for the same
     * env are idempotent (both converge to the same row, last write wins).
     *
     * @param id Environment ID
     */
    @Operation(description = "Kick off an async drift recompute for an env. Poll /compareDesignWithRuntime for the result.")
    @PostMapping(value = "/refreshDrift")
    @Parameter(name = "id", description = "Environment ID")
    public ApiResponse<Void> refreshDrift(@RequestParam Long id) {
        applicationEventPublisher.publishEvent(new DesignAppEnvDriftRefreshEvent(id));
        return ApiResponse.success();
    }

    /**
     * Issue a fresh Ed25519 keypair for this env.
     * <p>
     * Writes the new private key (encrypted at rest) onto the env row and returns
     * the public key half — the operator copies this into the runtime's
     * {@code system.runtime-public-key} entry so the runtime recognises
     * requests signed with the new key. Calling this again atomically replaces the
     * keypair: previous signatures stop validating as soon as the operator updates
     * the runtime yml.
     *
     * @param id Environment ID
     * @return base64-encoded public key
     */
    @Operation(description = "Issue / rotate the Ed25519 keypair used to sign studio → runtime requests for this env.")
    @PostMapping(value = "/issueKey")
    @Parameter(name = "id", description = "Environment ID")
    public ApiResponse<DesignAppEnvService.IssuedKey> issueKey(@RequestParam Long id) {
        return ApiResponse.success(service.issueKey(id));
    }

    /**
     * Drift-repair entry point: overwrite design-time metadata with the already-cached
     * runtime drift for this env. Use when the operator has just inspected the drift
     * report and is accepting those exact changes as the new design-time truth.
     *
     * @param id Environment ID
     */
    @Operation(description = "Apply the cached runtime drift onto design-time metadata (accepts the current drift report as-is).")
    @PostMapping(value = "/applyDrift")
    @Parameter(name = "id", description = "Environment ID")
    public ApiResponse<Void> applyDrift(@RequestParam Long id) {
        service.applyDrift(id, true);
        return ApiResponse.success();
    }

    /**
     * First-time import entry point: refresh drift against the current runtime and then
     * apply it onto design-time metadata. Use when seeding a new studio app from a
     * runtime that already owns the authoritative metadata.
     *
     * @param id Environment ID
     */
    @Operation(description = "Refresh drift against the runtime, then overwrite design-time metadata with the result.")
    @PostMapping(value = "/importFromRuntime")
    @Parameter(name = "id", description = "Environment ID")
    public ApiResponse<Void> importFromRuntime(@RequestParam Long id) {
        service.applyDrift(id, false);
        return ApiResponse.success();
    }
}
