package io.softa.starter.metadata.seed;

import java.util.List;

/**
 * What one release changed in a tenant seed file, as far as tenants are concerned: the rows it added and
 * the rows it removed, as {@code Model/preId} keys. Rows it modified are not listed — a tenant's copy of a
 * row is the tenant's.
 *
 * @param file    file name
 * @param added   rows the release added
 * @param removed rows the release removed
 */
public record TenantFileChange(String file, List<String> added, List<String> removed) {

    public TenantFileChange {
        added = added == null ? List.of() : List.copyOf(added);
        removed = removed == null ? List.of() : List.copyOf(removed);
    }

    public boolean reachesTenants() {
        return !added.isEmpty() || !removed.isEmpty();
    }
}
