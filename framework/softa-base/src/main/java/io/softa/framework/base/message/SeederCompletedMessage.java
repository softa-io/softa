package io.softa.framework.base.message;

/**
 * MQ payload a seeder publishes after it finishes seeding one tenant. Two kinds of subscriber consume it:
 * the tenant-starter coordinator (folds it into the tenant's provisioning progress → READY once the
 * expected set is complete), and any downstream business seeder that depends on this one (fires only after
 * its upstream reports done — the ordering mechanism in mode-2). The {@code seederKey} is opaque to the
 * framework — business modules assign and interpret it. A framework DTO in {@code base.message} so the
 * producer (any business module) and consumers (tenant-starter + other modules) share it without a cycle.
 *
 * @param tenantId  the tenant being seeded
 * @param seederKey the reporting seeder's opaque key (e.g. "pre-data", "corehr")
 * @param success   whether the seed step succeeded. Seeders currently publish only {@code true} (a failure
 *                  is retried via redelivery, not reported as false); {@code false} is reserved for a future
 *                  DLQ handler and would drive the coordinator's markSeederFailed path. Tenant-level FAILED
 *                  meanwhile comes from tenant-starter's timeout guard, not from this flag.
 */
public record SeederCompletedMessage(Long tenantId, String seederKey, boolean success) {
}
