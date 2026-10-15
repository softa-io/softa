package io.softa.starter.metadata.service.impl;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A tenant's seed files are loaded into it whole only while it is being set up: once it is, a whole load
 * would overwrite what it changed, so it is refused before anything is read or written.
 */
class SysPreDataSetupGuardTest {

    private static final Long TENANT = 7L;
    private static final String TENANT_MODEL = "TenantInfo";

    private final ModelService<?> modelService = mock(ModelService.class);
    @SuppressWarnings({"unchecked", "rawtypes"})
    private final SysPreDataServiceImpl service = new SysPreDataServiceImpl((ModelService) modelService);

    private static boolean previousMultiTenancy;

    @BeforeAll
    static void ensureSystemConfig() {
        // The framework's exceptions reach I18n through SystemConfig.env, which a raw unit test must seed;
        // the flag is restored because the surefire JVM is shared with tests that read it.
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
        previousMultiTenancy = SystemConfig.env.isEnableMultiTenancy();
        SystemConfig.env.setEnableMultiTenancy(true);
    }

    @AfterAll
    static void restoreSystemConfig() {
        SystemConfig.env.setEnableMultiTenancy(previousMultiTenancy);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Draft", "Initializing"})
    void aTenantBeingSetUpMayBeLoadedWhole(String status) {
        withTenantStatus(status, () -> assertThat(service.isSettingUp(TENANT)).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Active", "Suspended", "Closed"})
    void aTenantThatFinishedItsSetupMayNot(String status) {
        withTenantStatus(status, () -> assertThat(service.isSettingUp(TENANT)).isFalse());
    }

    @Test
    void anApplicationWithoutTenantRecordsHasNothingToProtect() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel(TENANT_MODEL)).thenReturn(false);
            assertThat(service.isSettingUp(TENANT)).isTrue();
        }
    }

    @Test
    void aTenantWithNoRecordHasNothingToProtect() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel(TENANT_MODEL)).thenReturn(true);
            when(modelService.searchList(eq(TENANT_MODEL), any(FlexQuery.class))).thenReturn(List.of());
            assertThat(service.isSettingUp(TENANT)).isTrue();
        }
    }

    @Test
    void loadingTheFilesOfASetUpTenantIsRefused() {
        withTenantStatus("Active", () -> assertThatThrownBy(
                () -> service.loadPreTenantData(List.of("Role.Tenant.json"), TENANT))
                .hasMessageContaining("has finished its setup"));
    }

    private void withTenantStatus(String status, Runnable body) {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel(TENANT_MODEL)).thenReturn(true);
            when(modelService.searchList(eq(TENANT_MODEL), any(FlexQuery.class)))
                    .thenReturn(List.of(Map.of("status", status)));
            body.run();
        }
    }
}
