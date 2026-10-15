package io.softa.starter.metadata.seed;

import org.junit.jupiter.api.Test;

import io.softa.starter.metadata.entity.SeedSyncBatch;
import io.softa.starter.metadata.enums.SeedSyncScope;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a batch records it covers, so the sync history tells a platform-only load from one that reaches
 * tenants without opening it.
 */
class SeedSyncBatchScopeTest {

    @Test
    void aBatchLoadingOnlyPlatformFilesCoversThePlatform() {
        SeedSyncBatch batch = described(true, false, 0);

        assertThat(batch.getScope()).isEqualTo(SeedSyncScope.PLATFORM);
        assertThat(batch.getTenantFileCount()).isZero();
    }

    @Test
    void aBatchReachingTenantsOnlyCoversTheTenants() {
        SeedSyncBatch batch = described(false, true, 28);

        assertThat(batch.getScope()).isEqualTo(SeedSyncScope.TENANTS);
        assertThat(batch.getTenantFileCount()).isEqualTo(28);
    }

    @Test
    void aBatchDoingBothCoversBoth() {
        assertThat(described(true, true, 2).getScope()).isEqualTo(SeedSyncScope.PLATFORM_AND_TENANTS);
    }

    private static SeedSyncBatch described(boolean platform, boolean tenants, int tenantFiles) {
        SeedSyncBatch batch = new SeedSyncBatch();
        SeedSyncService.describe(batch, platform, tenants, tenantFiles);
        return batch;
    }
}
