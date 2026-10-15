package io.softa.starter.metadata.seed;

/**
 * Bring one tenant up to date in a seed sync batch — one message per {@code SeedSyncTask}.
 *
 * @param batchId  the batch
 * @param taskId   the task, which carries everything else
 * @param tenantId the tenant, for logs and routing
 */
public record TenantSeedSyncMessage(Long batchId, Long taskId, Long tenantId) {
}
