package io.softa.starter.permission.service;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.index.EndpointIndex;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The import or export of a model is held by the permission registered for that model's import or
 * export — holding another model's does not lend it, and a tenant admin is asked the same way.
 */
class ImportModelGrantTest {

    private static final String EMPLOYEE_IMPORT = "permission.employee.import";
    private static final String DEPARTMENT_IMPORT = "permission.department.import";
    private static final String EMPLOYEE_EXPORT = "permission.employee.export";
    /** Registered under a platform navigation, so never in a tenant admin's permission set. */
    private static final String TENANT_INFO_EXPORT = "permission.system.tenant-info.export";

    private PermissionInfo pi;
    private EndpointIndex index;
    private PermissionServiceImpl service;
    private MockedStatic<ModelManager> models;
    private SystemConfig previousConfig;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pi = new PermissionInfo();
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenAnswer(inv -> pi);
        index = mock(EndpointIndex.class);
        when(index.actionPermissions(Mockito.anyString(), Mockito.anyString())).thenReturn(Set.of());
        when(index.actionPermissions("Employee", "import")).thenReturn(Set.of(EMPLOYEE_IMPORT));
        when(index.actionPermissions("Department", "import")).thenReturn(Set.of(DEPARTMENT_IMPORT));
        when(index.actionPermissions("Employee", "export")).thenReturn(Set.of(EMPLOYEE_EXPORT));
        when(index.actionPermissions("TenantInfo", "export")).thenReturn(Set.of(TENANT_INFO_EXPORT));
        service = new PermissionServiceImpl(provider, mock(ScopeRuleCompiler.class), null,
                mock(ModelService.class), mock(ScopeApplicabilityResolver.class), () -> index);

        previousConfig = SystemConfig.env;
        SystemConfig.env = new SystemConfig();
        SystemConfig.env.setEnableMultiTenancy(true);
        models = Mockito.mockStatic(ModelManager.class, Mockito.CALLS_REAL_METHODS);
        stubModel("Position", true);
        stubModel("UserIdentity", false);
    }

    @AfterEach
    void tearDown() {
        models.close();
        SystemConfig.env = previousConfig;
    }

    private void stubModel(String name, boolean multiTenant) {
        MetaModel model = mock(MetaModel.class);
        when(model.isMultiTenant()).thenReturn(multiTenant);
        models.when(() -> ModelManager.getModel(name)).thenReturn(model);
    }

    private <T> T asCaller(java.util.function.Supplier<T> call) {
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);
        return ContextHolder.callWith(ctx, call::get);
    }

    private boolean mayImport(String model) {
        return asCaller(() -> service.hasModelImportGrant(model));
    }

    private boolean mayExport(String model) {
        return asCaller(() -> service.hasModelExportGrant(model));
    }

    @Test
    @DisplayName("the import of one model does not open another's")
    void anotherModelsImportDoesNotGrant() {
        pi.setPermissions(Set.of(DEPARTMENT_IMPORT));

        assertThat(mayImport("Department")).isTrue();
        assertThat(mayImport("Employee")).isFalse();
    }

    @Test
    @DisplayName("a tenant's own model with no import permission registered stays importable")
    void anUnregisteredTenantModelIsGranted() {
        pi.setPermissions(Set.of(DEPARTMENT_IMPORT));

        assertThat(mayImport("Position")).isTrue();
    }

    @Test
    @DisplayName("a shared model with nothing registered is not moved in or out from inside a tenant")
    void anUnregisteredSharedModelIsRefused() {
        pi.setPermissions(Set.of(DEPARTMENT_IMPORT));

        assertThat(mayExport("UserIdentity")).isFalse();
        assertThat(mayImport("UserIdentity")).isFalse();
    }

    @Test
    @DisplayName("a tenant admin moves only what its own permission set holds")
    void aTenantAdminIsAskedLikeEveryoneElse() {
        pi.setRoleCodes(Set.of(PermissionInfo.CODE_TENANT_ADMIN));
        pi.setPermissions(Set.of(EMPLOYEE_EXPORT, EMPLOYEE_IMPORT));

        assertThat(mayExport("Employee")).isTrue();
        assertThat(mayImport("Employee")).isTrue();
        // A platform model's export sits under a platform navigation, outside the tenant admin's set.
        assertThat(mayExport("TenantInfo")).isFalse();
        // A shared model nothing registered: every tenant's rows, so refused to a tenant admin too.
        assertThat(mayExport("UserIdentity")).isFalse();
        assertThat(mayImport("UserIdentity")).isFalse();
    }

    @Test
    @DisplayName("the platform administrator still moves any model")
    void theSuperAdminPasses() {
        pi.setRoleCodes(Set.of(PermissionInfo.CODE_SUPER_ADMIN));
        pi.setPermissions(Set.of());

        assertThat(mayExport("TenantInfo")).isTrue();
        assertThat(mayExport("UserIdentity")).isTrue();
    }

    @Test
    @DisplayName("without multi-tenancy an unregistered shared model keeps its old default")
    void singleTenantKeepsTheDefault() {
        SystemConfig.env.setEnableMultiTenancy(false);
        pi.setPermissions(Set.of(DEPARTMENT_IMPORT));

        assertThat(mayExport("UserIdentity")).isTrue();
    }
}
