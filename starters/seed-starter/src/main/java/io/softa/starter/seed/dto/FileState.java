package io.softa.starter.seed.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where one seed file stands in one scope, read off its load records.
 */
@Schema(name = "FileState", description = "Where one seed file stands in one scope, read off its load records. "
        + "NOT_LOADED: never loaded here, runs next time. LOADED: loaded, and the file has not changed since. "
        + "CHANGED: loaded, then changed, and reloads — a platform file, or a tenant file in mode append or "
        + "overwrite. "
        + "CHANGED_AFTER_LOAD: loaded, then changed, and does not run again unless reapplied — a run-once tenant "
        + "file. "
        + "LOADED_BY_NEWER_BUILD: loaded by a newer build than the one running, after a rollback; it does not "
        + "load from this one. FAILED: the latest attempt failed, runs again next time.")
public enum FileState {
    /** Never loaded here. Runs next time. */
    NOT_LOADED,
    /** Loaded, and the file has not changed since. */
    LOADED,
    /**
     * Loaded, then changed, and it reloads when changed — a platform file, or a tenant file in mode append or
     * overwrite.
     */
    CHANGED,
    /**
     * Loaded, then changed, and it does not reload — a tenant file that runs once. The change reaches new
     * tenants only; existing ones get new rows from a patch file and changed rows from a data migration, or the
     * whole file again when the platform admin reapplies it.
     */
    CHANGED_AFTER_LOAD,
    /**
     * Loaded by a newer build than the one running, and the file differs: an older build is deployed, after a
     * rollback. It does not reload — that would put this build's older content over the newer one.
     */
    LOADED_BY_NEWER_BUILD,
    /** The latest attempt failed. Runs again next time. */
    FAILED;

    /** Whether the next load or run picks the file up. */
    public boolean pending() {
        return this == NOT_LOADED || this == CHANGED || this == FAILED;
    }
}
