package io.softa.starter.seed.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.enums.SystemRole;
import io.softa.framework.orm.annotation.RequireRole;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.seed.dto.*;
import io.softa.starter.seed.service.PlatformSeedLoader;
import io.softa.starter.seed.service.SeedLoadLog;
import io.softa.starter.seed.service.TenantSeedRunner;

/**
 * Seed data operations, for the platform admin only: load the platform files, and run the tenant files or reapply
 * them, which the Seed Data page does. An environment's first platform load initializes it as well. The record of every operation
 * is the {@code SeedLoadBatch} / {@code SeedLoadRecord} models, read through the generic model API.
 */
@Tag(name = "Seed Data")
@RestController
@RequestMapping("/SeedData")
public class SeedDataController {

    private final PlatformSeedLoader platformLoader;
    private final TenantSeedRunner tenantRunner;
    private final SeedLoadLog loadLog;

    public SeedDataController(PlatformSeedLoader platformLoader, TenantSeedRunner tenantRunner,
                              SeedLoadLog loadLog) {
        this.platformLoader = platformLoader;
        this.tenantRunner = tenantRunner;
        this.loadLog = loadLog;
    }

    @Operation(summary = "The platform seed files of the running build against what the platform last loaded")
    @GetMapping("/platform")
    @RequireRole(SystemRole.SYSTEM_ROLE_ADMIN)
    public ApiResponse<PlatformSeedStatus> platformStatus() {
        return ApiResponse.success(platformLoader.status());
    }

    @Operation(summary = "Load the pending platform files — all of them, or the ones named — in one transaction, "
            + "leaving out the files the build owns, which load at startup. An environment's first load initializes "
            + "it first: it records the tenant files tenants already have rows of as run, changing no data")
    @PostMapping("/platform/load")
    @RequireRole(SystemRole.SYSTEM_ROLE_ADMIN)
    public ApiResponse<PlatformLoadResult> loadPlatform(@RequestBody(required = false) PlatformLoadRequest request) {
        return ApiResponse.success(platformLoader.load(request == null ? null : request.fileNames()));
    }

    @Operation(summary = "What a run of every set-up tenant would do")
    @GetMapping("/tenants/pending")
    @RequireRole(SystemRole.SYSTEM_ROLE_ADMIN)
    public ApiResponse<TenantPendingSummary> tenantPending() {
        return ApiResponse.success(tenantRunner.pending());
    }

    @Operation(summary = "These tenants' seed files in numbers")
    @GetMapping("/tenants/summary")
    @RequireRole(SystemRole.SYSTEM_ROLE_ADMIN)
    public ApiResponse<List<TenantSeedSummary>> tenantSummaries(@RequestParam List<Long> tenantIds) {
        return ApiResponse.success(tenantRunner.summaries(tenantIds));
    }

    @Operation(summary = "Every tenant file a tenant is due, and where each stands")
    @GetMapping("/tenants/detail")
    @RequireRole(SystemRole.SYSTEM_ROLE_ADMIN)
    public ApiResponse<TenantSeedDetail> tenantDetail(@RequestParam Long tenantId) {
        return ApiResponse.success(tenantRunner.detail(tenantId));
    }

    @Operation(summary = "Run the pending tenant files in the given tenants, or in every set-up tenant, in the "
            + "background")
    @PostMapping("/tenants/run")
    @RequireRole(SystemRole.SYSTEM_ROLE_ADMIN)
    public ApiResponse<BatchStarted> runTenants(@RequestBody(required = false) TenantRunRequest request) {
        return ApiResponse.success(new BatchStarted(tenantRunner.run(request == null ? null : request.tenantIds())));
    }

    @Operation(summary = "Run the named tenant files again in the given tenants, whatever their state, in the "
            + "background: each file loads whole over the tenant's copy, as an overwrite file does, so what the "
            + "tenant changed in those rows is lost")
    @PostMapping("/tenants/reapply")
    @RequireRole(SystemRole.SYSTEM_ROLE_ADMIN)
    public ApiResponse<BatchStarted> reapplyTenantFiles(@RequestBody TenantReapplyRequest request) {
        return ApiResponse.success(new BatchStarted(tenantRunner.reapply(request.tenantIds(), request.fileNames())));
    }

    @Operation(summary = "The platform load, tenant run, reapply or initialization in progress, or null")
    @GetMapping("/batches/running")
    @RequireRole(SystemRole.SYSTEM_ROLE_ADMIN)
    public ApiResponse<RunningBatch> runningBatch() {
        return ApiResponse.success(loadLog.running().orElse(null));
    }
}
