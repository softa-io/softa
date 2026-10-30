package io.softa.starter.seed.dto;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.starter.seed.enums.SeedLoadStatus;
import io.softa.starter.seed.enums.SeedLoadType;

/**
 * The seed data operation in progress.
 */
@Schema(name = "RunningBatch", description = "The seed data operation in progress")
public record RunningBatch(

        @Schema(description = "Batch id")
        Long id,

        @Schema(description = "Platform, Tenant, Reapply or Initialize")
        SeedLoadType type,

        @Schema(description = "Running")
        SeedLoadStatus status,

        @Schema(description = "Tenants the batch covers; null for a platform load")
        Integer totalTenants,

        @Schema(description = "Tenants done so far, failed ones included")
        Integer finishedTenants,

        @Schema(description = "Tenants that failed so far")
        Integer failedTenants,

        @Schema(description = "When it started")
        LocalDateTime startTime,

        @Schema(description = "When it last made progress")
        LocalDateTime lastProgressTime,

        @Schema(description = "No progress for a while: the instance running it went down. Running again continues it")
        boolean stale
) {
}
