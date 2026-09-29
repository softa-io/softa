package io.softa.starter.metadata.seed;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What the platform admin needs to decide on a sync.
 *
 * @param files           every platform seed file, in load order
 * @param pendingCount    files with content still to load
 * @param rolledBackCount files older than what the database holds
 * @param affectedTenants tenants that read the platform data (every tenant not closed)
 * @param runningBatchId  the batch in progress, or null
 * @param loadedFiles     files the batch in progress has loaded so far
 * @param totalFiles      files the batch in progress set out to load
 * @param canSync         whether a sync can start now
 * @param blockReason     why it cannot, or null
 * @param tenantCreationBlocker why a tenant cannot be created now, or null when it can
 * @param appVersion      version of the running application
 * @param buildTime       build time of the running application
 */
public record SeedSyncStatusView(List<SeedFileState> files, int pendingCount, int rolledBackCount,
                                 long affectedTenants, Long runningBatchId, Integer loadedFiles,
                                 Integer totalFiles, boolean canSync, String blockReason,
                                 String tenantCreationBlocker, String appVersion, LocalDateTime buildTime) {
}
