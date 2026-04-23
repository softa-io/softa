package io.softa.starter.studio.release.event;

/**
 * Trigger an asynchronous drift recomputation for a single env.
 * <p>
 * Published from two places:
 * <ul>
 *   <li>{@link DesignDeploymentSnapshotListener} — once, after a successful deployment
 *       has rebuilt the snapshot, so the cached drift reflects the new baseline
 *       instead of stale data from the previous snapshot.</li>
 *   <li>{@code DesignAppEnvController#refreshDrift} — when an operator manually clicks
 *       the UI "check drift" button and the controller must return without blocking
 *       on the expensive per-model runtime-export fan-out.</li>
 * </ul>
 */
public record DesignAppEnvDriftRefreshEvent(Long envId) {}
