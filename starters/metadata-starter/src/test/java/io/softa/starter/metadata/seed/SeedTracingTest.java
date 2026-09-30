package io.softa.starter.metadata.seed;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import io.softa.starter.metadata.entity.SysPreData;
import io.softa.starter.metadata.service.SysPreDataService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tracing the seed data of a tenant set up before bindings recorded their file: which file each row belongs
 * to, and what tracing would give the tenant — the files it never had, and the rows it has no binding for.
 */
class SeedTracingTest {

    private static final Long TENANT = 7L;
    private static final SeedManifest MANIFEST = SeedManifestReader.read(new ByteArrayInputStream("""
            - key: base
              files:
                - file: MailTemplate.Default.json
                  level: TENANT
                - file: Role.Tenant.json
                  level: TENANT
                  retired: [role.old]
            - key: leave
              module: leave
              files:
                - file: LeaveType.Default.json
                  level: TENANT
            """.getBytes(StandardCharsets.UTF_8)), "test.yml");

    private SysPreDataService preDataService;
    private EntitlementService entitlement;
    private TenantSeedScope scope;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        preDataService = mock(SysPreDataService.class);
        entitlement = mock(EntitlementService.class);
        ObjectProvider<SeedManifest> manifest = mock(ObjectProvider.class);
        when(manifest.getObject()).thenReturn(MANIFEST);
        ObjectProvider<EntitlementService> entitlements = mock(ObjectProvider.class);
        when(entitlements.getIfAvailable()).thenReturn(entitlement);
        scope = new TenantSeedScope(manifest, entitlements, mock(ModelService.class), preDataService);
        String dir = SeedLevel.TENANT.getDataDir();
        when(preDataService.rowKeysOf(dir, "MailTemplate.Default.json"))
                .thenReturn(keys("MailTemplate/mt.invite", "MailTemplate/mt.reset"));
        // A row two files declare: it belongs to the one loaded first.
        when(preDataService.rowKeysOf(dir, "Role.Tenant.json"))
                .thenReturn(keys("Role/role.admin", "RoleNavigation/nav.admin.home", "MailTemplate/mt.reset"));
        when(preDataService.rowKeysOf(dir, "LeaveType.Default.json"))
                .thenReturn(keys("LeaveType/lt.annual", "LeaveType/lt.sick"));
    }

    @Test
    void eachRowBelongsToTheFirstFileThatDeclaresIt() {
        Map<String, String> files = scope.fileOfRowKey(SeedLevel.TENANT);

        assertThat(files).containsEntry("MailTemplate/mt.reset", "MailTemplate.Default.json")
                .containsEntry("RoleNavigation/nav.admin.home", "Role.Tenant.json")
                .containsEntry("LeaveType/lt.sick", "LeaveType.Default.json");
    }

    @Test
    void aRetiredPreIdBelongsToTheFileThatRetiredItWhateverItsModel() {
        assertThat(scope.fileOfRowKey(SeedLevel.TENANT))
                .containsEntry(TenantSeedScope.RETIRED_KEY_PREFIX + "role.old", "Role.Tenant.json");
    }

    @Test
    void thePreviewCountsTheFilesNeverHadAndTheRowsWithoutABinding() {
        when(entitlement.entitledModules(TENANT)).thenReturn(Set.of("leave"));
        // Has one mail template and the admin role, from before bindings recorded their file; no leave type.
        when(preDataService.searchList(any(Filters.class))).thenReturn(List.of(
                binding("MailTemplate", "mt.invite"), binding("Role", "role.admin")));

        List<TenantTracePreview> previews = noModels(() -> scope.tracePreview(List.of(TENANT)));

        assertThat(previews).singleElement().satisfies(preview -> {
            assertThat(preview.untracedBindings()).isEqualTo(2);
            assertThat(preview.filesNeverHad()).containsExactly("LeaveType.Default.json");
            // mt.reset, nav.admin.home, and the two leave types.
            assertThat(preview.rowsWithoutBinding()).isEqualTo(4);
        });
    }

    private static SysPreData binding(String model, String preId) {
        SysPreData binding = new SysPreData();
        binding.setTenantId(TENANT);
        binding.setModel(model);
        binding.setPreId(preId);
        return binding;
    }

    private static Set<String> keys(String... keys) {
        return new LinkedHashSet<>(List.of(keys));
    }

    private static <T> T noModels(Supplier<T> action) {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel(eq("TenantInfo"))).thenReturn(false);
            mm.when(() -> ModelManager.existModel(eq("Company"))).thenReturn(false);
            return action.get();
        }
    }
}
