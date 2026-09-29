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
import io.softa.starter.metadata.seed.SeedSyncStatusView;

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

    public SeedSyncController(SeedSyncService seedSyncService) {
        this.seedSyncService = seedSyncService;
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
}
