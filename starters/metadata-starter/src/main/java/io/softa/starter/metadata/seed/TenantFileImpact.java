package io.softa.starter.metadata.seed;

/**
 * What syncing a pending tenant file would do to the tenants set up before it.
 *
 * @param file           file name
 * @param addedRows      rows this release added — created in every tenant reached that does not have them
 * @param removedRows    rows this release removed; only an invalid-columns push takes them out of tenants
 * @param push           what the file pushes into rows tenants already have
 * @param holders        tenants that have rows from the file
 * @param onlyNewTenants the release only changed rows tenants already have: they keep their copy, and the
 *                       change reaches tenants set up from now on
 */
public record TenantFileImpact(String file, int addedRows, int removedRows, SeedPushScope push, long holders,
                               boolean onlyNewTenants) {
}
