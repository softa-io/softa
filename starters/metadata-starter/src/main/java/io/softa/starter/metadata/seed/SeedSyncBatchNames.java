package io.softa.starter.metadata.seed;

import java.util.ArrayList;
import java.util.List;

import io.softa.starter.metadata.enums.SeedSyncTriggerType;

/**
 * The one line that names a sync batch in the history, written when the batch is created: what started it
 * and what it reaches, so a platform admin can tell the batches apart without opening each one.
 */
final class SeedSyncBatchNames {

    /** Up to this many files are named one by one; more are counted. */
    private static final int FILES_NAMED = 3;
    private static final int MAX_LENGTH = 256;

    private SeedSyncBatchNames() {
    }

    /**
     * A sync of a release's pending files.
     *
     * @param platformFiles   platform files it loads
     * @param tenantFiles     tenant files whose new rows reach tenants
     * @param newTenantsOnly  pending tenant files that reach no existing tenant: only their version is recorded
     * @param selectedTenants the tenants it is limited to, or null for every tenant
     */
    static String sync(int platformFiles, int tenantFiles, int newTenantsOnly, Integer selectedTenants) {
        List<String> parts = new ArrayList<>();
        if (platformFiles > 0) {
            parts.add(count(platformFiles, "platform file"));
        }
        if (tenantFiles > 0) {
            parts.add(count(tenantFiles, "tenant file"));
        }
        StringBuilder name = new StringBuilder("Sync ").append(String.join(" and ", parts));
        if (tenantFiles > 0 && selectedTenants != null) {
            name.append(" to ").append(count(selectedTenants, "selected tenant"));
        }
        if (newTenantsOnly > 0) {
            name.append(parts.isEmpty() ? "" : "; ").append(count(newTenantsOnly, "tenant file"))
                    .append(" for new tenants only");
        }
        return fit(name.toString());
    }

    /** Named platform files loaded through the seed data API. */
    static String load(List<String> files) {
        return fit("Load " + (files.size() <= FILES_NAMED ? String.join(", ", files) : count(files.size(), "platform file")));
    }

    static String retry(int failedTenants, Long batchId) {
        return fit("Retry " + count(failedTenants, "failed tenant") + " of batch " + batchId);
    }

    static String provision(String tenantCode, int tenantFiles) {
        return fit("Set up tenant " + tenantCode + " with " + count(tenantFiles, "tenant file"));
    }

    /**
     * Tenants given the files they are due and never had: after a plan change, or a company in a new country.
     *
     * @param tenantCodes the tenants, by code
     * @param files       the files they get between them
     */
    static String reconcile(SeedSyncTriggerType triggerType, List<String> tenantCodes, int files) {
        String reason = triggerType == SeedSyncTriggerType.COUNTRY_ADDED ? "New company country" : "Plan change";
        String newFiles = count(files, "new file");
        return fit(tenantCodes.size() == 1
                ? reason + " for " + tenantCodes.getFirst() + ": " + newFiles
                : reason + ": " + count(tenantCodes.size(), "tenant") + " get " + newFiles);
    }

    /**
     * Tracing seed data set up before bindings recorded their file.
     *
     * @param selectedTenants the tenants it is limited to, or null for every tenant and the shared rows
     * @param tenants         the tenants it reaches
     */
    static String trace(Integer selectedTenants, int tenants) {
        if (selectedTenants != null) {
            return fit("Trace seed data of " + count(selectedTenants, "selected tenant"));
        }
        return fit(tenants == 0 ? "Trace the shared seed data"
                : "Trace seed data of all " + count(tenants, "tenant") + " and the shared rows");
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String fit(String name) {
        return name.length() <= MAX_LENGTH ? name : name.substring(0, MAX_LENGTH - 1) + "…";
    }
}
