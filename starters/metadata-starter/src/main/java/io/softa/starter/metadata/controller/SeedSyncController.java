package io.softa.starter.metadata.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.entity.SeedSyncBatch;
import io.softa.starter.metadata.seed.SeedSyncService;
import io.softa.starter.metadata.seed.SeedPackagePreview;
import io.softa.starter.metadata.seed.SeedSyncStatusView;
import io.softa.starter.metadata.seed.TenantSeedScope;

/**
 * The platform admin's view of the seed data, and the one way to apply a release's changes: the platform
 * files once, then every tenant brought up to date with the tenant files. Meant for the platform only: the
 * host application restricts {@code /SeedSync/**} to it.
 */
@Tag(name = "Seed Sync")
@RestController
@RequestMapping("/SeedSync")
public class SeedSyncController {

    private final SeedSyncService seedSyncService;
    private final TenantSeedScope tenantSeedScope;

    public SeedSyncController(SeedSyncService seedSyncService, TenantSeedScope tenantSeedScope) {
        this.seedSyncService = seedSyncService;
        this.tenantSeedScope = tenantSeedScope;
    }

    @Operation(summary = "Seed files of the running release against what the database has recorded")
    @GetMapping("/status")
    public ApiResponse<SeedSyncStatusView> status() {
        return ApiResponse.success(seedSyncService.status());
    }

    @Operation(summary = "Start syncing every pending seed file; returns the batch id, or null when there is "
            + "nothing to sync")
    @PostMapping("/sync")
    public ApiResponse<Long> sync(@RequestParam(required = false) List<Long> tenantIds) {
        return ApiResponse.success(seedSyncService.sync(tenantIds).map(SeedSyncBatch::getId).orElse(null));
    }

    @Operation(summary = "Re-run the tenants a batch failed on, as a new batch; returns its id, or null when the "
            + "batch has no failed tenant")
    @PostMapping("/retry")
    public ApiResponse<Long> retry(@RequestParam Long batchId) {
        return ApiResponse.success(seedSyncService.retry(batchId).map(SeedSyncBatch::getId).orElse(null));
    }

    @Operation(summary = "The tenant seed packages, with their files, a tenant on this plan in this country is set up with")
    @GetMapping("/packagePreview")
    public ApiResponse<List<SeedPackagePreview>> packagePreview(@RequestParam(required = false) String planId,
                                                                @RequestParam(required = false) String country) {
        return ApiResponse.success(tenantSeedScope.preview(planId, country));
    }
}
