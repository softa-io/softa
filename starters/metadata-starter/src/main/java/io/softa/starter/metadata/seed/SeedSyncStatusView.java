package io.softa.starter.metadata.seed;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What the platform admin needs to decide on a sync.
 *
 * @param files                 every seed file, platform then tenant, each in load order
 * @param pendingCount          files with changes still to sync
 * @param rolledBackCount       files older than what the database holds
 * @param tenantBaseline        no tenant file has a version yet: the next sync records them as the baseline
 *                              and changes no tenant
 * @param platformPendingCount  pending platform files — loaded once, for everyone
 * @param tenantPendingCount    pending tenant files — brought up to date in every synced tenant
 * @param affectedTenants       tenants a sync would give a task — every active or suspended one when a pending
 *                              tenant file adds rows or removes invalid columns, else none
 * @param tenantImpacts         what each pending tenant file would do to the tenants set up before it; empty
 *                              while a sync runs or when the next sync records the baseline
 * @param untracedTenants       active or suspended tenants whose seed data is not traced to its files yet
 * @param untracedPlatformBindings bindings of the shared rows and the platform tenant not traced yet
 * @param traceBlockReason      why seed data cannot be traced now, or null when it can
 * @param runningBatchId        the batch in progress, or null
 * @param loadedFiles           platform files the batch in progress has loaded so far
 * @param totalFiles            platform files the batch in progress set out to load
 * @param finishedTenants       tenants the batch in progress is done with
 * @param totalTenants          tenants the batch in progress set out to bring up to date
 * @param canSync               whether a sync can start now
 * @param blockReason           why it cannot, or null
 * @param tenantCreationBlocker why a tenant cannot be created now, or null when it can
 * @param appVersion            version of the running application
 * @param buildTime             build time of the running application
 */
public record SeedSyncStatusView(List<SeedFileState> files, int pendingCount, int rolledBackCount,
                                 boolean tenantBaseline, int platformPendingCount, int tenantPendingCount, long affectedTenants,
                                 List<TenantFileImpact> tenantImpacts, int untracedTenants,
                                 long untracedPlatformBindings, String traceBlockReason,
                                 Long runningBatchId, Integer loadedFiles, Integer totalFiles,
                                 Integer finishedTenants, Integer totalTenants, boolean canSync, String blockReason,
                                 String tenantCreationBlocker, String appVersion, LocalDateTime buildTime) {
}
