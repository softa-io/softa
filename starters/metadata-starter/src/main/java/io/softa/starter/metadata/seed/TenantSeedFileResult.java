package io.softa.starter.metadata.seed;

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
 * @param notes   rows not applied, and why
 */
public record TenantSeedFileResult(String file, int created, int claimed, int skipped, int pushed, int removed,
                                   List<String> notes) {

    public TenantSeedFileResult {
        notes = List.copyOf(notes);
    }

    /** Whether the file changed anything in the tenant. */
    public boolean changedAnything() {
        return created + claimed + pushed + removed > 0;
    }
}
