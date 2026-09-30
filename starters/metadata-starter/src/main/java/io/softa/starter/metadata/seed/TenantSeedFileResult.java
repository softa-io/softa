package io.softa.starter.metadata.seed;

import java.util.ArrayList;
import java.util.List;

/**
 * What bringing one tenant seed file up to date did in one tenant.
 *
 * @param file    file name
 * @param created rows the tenant did not have, created from the file
 * @param claimed rows the tenant already had under the same business key, bound to the file without
 *                being changed
 * @param skipped rows the tenant already has from the file, left as they are — including ones it deleted
 * @param pushed  nested items added under rows the tenant already had (a declared push)
 * @param removed rows removed because what they describe no longer exists (a declared push)
 * @param traced  bindings the tenant already had that were traced to this file — when its seed data is
 *                traced once, after it was set up before bindings recorded their file
 * @param notes   rows not applied, and why
 */
public record TenantSeedFileResult(String file, int created, int claimed, int skipped, int pushed, int removed,
                                   int traced, List<String> notes) {

    public TenantSeedFileResult {
        notes = List.copyOf(notes);
    }

    /** What tracing a tenant's bindings did for one file. */
    public static TenantSeedFileResult tracedTo(String file, int traced, List<String> notes) {
        return new TenantSeedFileResult(file, 0, 0, 0, 0, 0, traced, notes);
    }

    /** Two results for the same file, as one. */
    public TenantSeedFileResult plus(TenantSeedFileResult other) {
        List<String> merged = new ArrayList<>(notes);
        merged.addAll(other.notes);
        return new TenantSeedFileResult(file, created + other.created, claimed + other.claimed,
                skipped + other.skipped, pushed + other.pushed, removed + other.removed, traced + other.traced,
                merged);
    }

    /** Whether the file changed anything in the tenant. */
    public boolean changedAnything() {
        return created + claimed + pushed + removed + traced > 0;
    }
}
