package io.softa.starter.metadata.seed;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.EntitlementService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.service.SysPreDataService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which tenant files a tenant is due, holds, and is missing. Countries are left out here — no tenant or
 * company model — so only files naming no country are due.
 */
class TenantSeedScopeTest {

    private static final Long TENANT = 7L;
    private static final SeedManifest MANIFEST = SeedManifestReader.read(new ByteArrayInputStream("""
            - key: base
              files:
                - file: MailTemplate.Default.json
                  level: TENANT
            - key: leave
              module: leave
              files:
                - file: LeaveType.Default.json
                  level: TENANT
            - key: overtime
              module: overtime
              files:
                - file: OvertimePolicy.Default.json
                  level: TENANT
            """.getBytes(StandardCharsets.UTF_8)), "test.yml");

    private EntitlementService entitlement;
    private SysPreDataService preDataService;
    private TenantSeedScope scope;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        entitlement = mock(EntitlementService.class);
        preDataService = mock(SysPreDataService.class);
        ObjectProvider<SeedManifest> manifest = mock(ObjectProvider.class);
        when(manifest.getObject()).thenReturn(MANIFEST);
        ObjectProvider<EntitlementService> entitlements = mock(ObjectProvider.class);
        when(entitlements.getIfAvailable()).thenReturn(entitlement);
        scope = new TenantSeedScope(manifest, entitlements, mock(ModelService.class), preDataService);
    }

    @Test
    void aTenantIsMissingTheFilesItIsDueAndNeverHad() {
        when(entitlement.entitledModules(TENANT)).thenReturn(Set.of("leave", "overtime"));
        holds("MailTemplate.Default.json", "LeaveType.Default.json");

        assertThat(noModels(() -> scope.missingFiles(TENANT))).containsExactly("OvertimePolicy.Default.json");
    }

    @Test
    void aTenantOnASmallerPlanStillReceivesWhatItHolds() {
        // Downgraded: overtime is no longer entitled, but the tenant has its rows.
        when(entitlement.entitledModules(TENANT)).thenReturn(Set.of("leave"));
        holds("MailTemplate.Default.json", "LeaveType.Default.json", "OvertimePolicy.Default.json");

        assertThat(noModels(() -> scope.reachedFiles(TENANT)))
                .containsExactly("MailTemplate.Default.json", "LeaveType.Default.json", "OvertimePolicy.Default.json");
        assertThat(noModels(() -> scope.missingFiles(TENANT))).isEmpty();
    }

    @Test
    void aTenantWhoseBindingsDoNotRecordTheirFileIsMissingNothing() {
        when(entitlement.entitledModules(TENANT)).thenReturn(Set.of("leave", "overtime"));
        when(preDataService.getDistinctFieldValue(any(), any(Filters.class))).thenReturn(List.of());
        // Set up before bindings recorded their file: some do not, and every file would look never had.
        when(preDataService.exist(any(Filters.class))).thenReturn(true, true);

        assertThat(noModels(() -> scope.missingFiles(TENANT))).isEmpty();
    }

    @Test
    void aTenantWithNoBindingsAtAllIsMissingNothing() {
        // Set up before seed data was recorded: nothing says what it has.
        when(entitlement.entitledModules(TENANT)).thenReturn(Set.of("leave", "overtime"));
        when(preDataService.getDistinctFieldValue(any(), any(Filters.class))).thenReturn(List.of());
        when(preDataService.exist(any(Filters.class))).thenReturn(false);

        assertThat(noModels(() -> scope.missingFiles(TENANT))).isEmpty();
    }

    /** The tenant has bindings from these files, every one recording its file. */
    private void holds(String... files) {
        when(preDataService.getDistinctFieldValue(any(), any(Filters.class))).thenReturn(List.of(files));
        // First lookup: some binding records its file. Second: none does not.
        when(preDataService.exist(any(Filters.class))).thenReturn(true, false);
    }

    private static <T> T noModels(Supplier<T> action) {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel(any())).thenReturn(false);
            return action.get();
        }
    }
}
