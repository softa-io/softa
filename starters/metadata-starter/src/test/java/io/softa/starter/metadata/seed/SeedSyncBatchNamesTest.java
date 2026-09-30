package io.softa.starter.metadata.seed;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.softa.starter.metadata.enums.SeedSyncTriggerType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line that names each batch in the sync history: what started it and what it reaches.
 */
class SeedSyncBatchNamesTest {

    @Test
    void aSyncNamesThePlatformAndTenantFilesItBrings() {
        assertThat(SeedSyncBatchNames.sync(1, 2, 0, null)).isEqualTo("Sync 1 platform file and 2 tenant files");
        assertThat(SeedSyncBatchNames.sync(3, 0, 0, null)).isEqualTo("Sync 3 platform files");
    }

    @Test
    void aSyncLimitedToSomeTenantsSaysHowMany() {
        assertThat(SeedSyncBatchNames.sync(0, 2, 0, 2)).isEqualTo("Sync 2 tenant files to 2 selected tenants");
    }

    @Test
    void tenantFilesThatReachNoExistingTenantAreSaidToBeForNewTenants() {
        assertThat(SeedSyncBatchNames.sync(3, 0, 40, null))
                .isEqualTo("Sync 3 platform files; 40 tenant files for new tenants only");
        assertThat(SeedSyncBatchNames.sync(0, 0, 1, null)).isEqualTo("Sync 1 tenant file for new tenants only");
    }

    @Test
    void aLoadNamesAFewFilesAndCountsMany() {
        assertThat(SeedSyncBatchNames.load(List.of("Plan.Builtin.json"))).isEqualTo("Load Plan.Builtin.json");
        assertThat(SeedSyncBatchNames.load(List.of("a.json", "b.json", "c.json", "d.json")))
                .isEqualTo("Load 4 platform files");
    }

    @Test
    void tenantBatchesNameTheirTenants() {
        assertThat(SeedSyncBatchNames.provision("acme", 37)).isEqualTo("Set up tenant acme with 37 tenant files");
        assertThat(SeedSyncBatchNames.reconcile(SeedSyncTriggerType.PLAN_CHANGE, List.of("acme"), 3))
                .isEqualTo("Plan change for acme: 3 new files");
        assertThat(SeedSyncBatchNames.reconcile(SeedSyncTriggerType.COUNTRY_ADDED, List.of("a", "b"), 5))
                .isEqualTo("New company country: 2 tenants get 5 new files");
        assertThat(SeedSyncBatchNames.retry(1, 895352391778435073L))
                .isEqualTo("Retry 1 failed tenant of batch 895352391778435073");
    }

    @Test
    void aTraceSaysWhetherItReachesEveryone() {
        assertThat(SeedSyncBatchNames.trace(2, 2)).isEqualTo("Trace seed data of 2 selected tenants");
        assertThat(SeedSyncBatchNames.trace(null, 31)).isEqualTo("Trace seed data of all 31 tenants and the shared rows");
        assertThat(SeedSyncBatchNames.trace(null, 0)).isEqualTo("Trace the shared seed data");
    }
}
