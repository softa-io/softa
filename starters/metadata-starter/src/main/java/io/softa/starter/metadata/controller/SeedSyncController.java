package io.softa.starter.metadata.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.entity.SeedSyncBatch;
import io.softa.starter.metadata.seed.SeedSyncService;
import io.softa.starter.metadata.seed.SeedSyncStatusView;

/**
 * The platform admin's view of the platform seed data, and the one way to apply a release's changes to
 * it. Meant for the platform only: the host application restricts {@code /SeedSync/**} to it.
 */
@Tag(name = "Seed Sync")
@RestController
@RequestMapping("/SeedSync")
public class SeedSyncController {

    private final SeedSyncService seedSyncService;

    public SeedSyncController(SeedSyncService seedSyncService) {
        this.seedSyncService = seedSyncService;
    }

    @Operation(summary = "Platform seed files of the running release against what the database has loaded")
    @GetMapping("/status")
    public ApiResponse<SeedSyncStatusView> status() {
        return ApiResponse.success(seedSyncService.status());
    }

    @Operation(summary = "Start loading every pending platform seed file; returns the batch id, or null when "
            + "there is nothing to load")
    @PostMapping("/sync")
    public ApiResponse<Long> sync() {
        return ApiResponse.success(seedSyncService.sync().map(SeedSyncBatch::getId).orElse(null));
    }
}
