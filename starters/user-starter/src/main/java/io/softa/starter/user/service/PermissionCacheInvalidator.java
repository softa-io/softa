package io.softa.starter.user.service;

import java.util.Set;

/**
 * Evicts PermissionInfo Redis cache entries. Driven by event listeners on the
 * impl side:
 * <ul>
 *   <li>{@code UserRoleRelChangedEvent}    → {@link #evictBatch} for the
 *       users whose role set changed</li>
 *   <li>{@code RoleNavigationChangedEvent} → {@link #evictByRole} for the
 *       role whose nav / permission / scope grants changed</li>
 *   <li>Domain events that shift a user's cached domain context → business
 *       modules subscribe to their own events and call {@link #evictOne}
 *       directly (the framework knows no such events)</li>
 * </ul>
 *
 * <p>Batch operations (e.g. after BulkUserRoleService) use {@link #evictBatch}
 * for a single Redis pipeline call instead of N round-trips.
 *
 * <p>No tenant-wide eviction entry point is exposed: system-level seed data
 * (navigation / permission / sensitive_field_set) only changes via
 * redeployment, so application restart flushes the in-memory indexes and
 * the Redis cache TTLs (1h) cover the transition window. Bulk admin ops
 * funnel through {@link #evictBatch} with an explicit user-id set instead.
 */
public interface PermissionCacheInvalidator {

    void evictOne(Long tenantId, Long userId);

    void evictBatch(Long tenantId, Set<Long> userIds);

    void evictByRole(Long tenantId, Long roleId);
}
