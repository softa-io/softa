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
import io.softa.framework.base.enums.BuiltinRole;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The export and the import of a model, asked by the endpoints every model shares.
 *
 * <p>{@code /export/*} and {@code /import/*} carry the model in a parameter, so the endpoint gate admits
 * anyone holding the export, or the import, of some model; this answer is the only one about this model.
 * It used to be yes for any administrator, a tenant's included. A tenant administrator reads past row
 * scope, and a model that is not multiTenant keeps every tenant's rows in one table — so a tenant could
 * export the platform's own tables (another tenant's send quota, the dead letters, the tenant registry)
 * and import into them (raise its own send quota), whatever its menus said.
 */
class SharedEndpointModelGrantTest {

    /** Shared by every tenant, its page the platform's. */
    private static final String QUOTA = "TenantMessageQuota";
    private static final String QUOTA_EXPORT = "permission.message.tenant-message-quota.export";
    private static final String QUOTA_IMPORT = "permission.message.tenant-message-quota.import";
    /** Shared by every tenant, and no page at all. */
    private static final String LEDGER = "SeedLoadRecord";
    /** The tenant's own, with a page. */
    private static final String EMPLOYEE = "Employee";
    private static final String EMPLOYEE_EXPORT = "permission.employee.export";
    private static final String EMPLOYEE_IMPORT = "permission.employee.import";
    /** The tenant's own, and no page: written and exported as part of its employee. */
    private static final String ADDRESS = "EmployeeAddress";

    private SystemConfig previousConfig;
    private MockedStatic<ModelManager> models;
    private PermissionInfo pi;
    private PermissionServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousConfig = SystemConfig.env;
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;

        models = Mockito.mockStatic(ModelManager.class);
        model(QUOTA, false);
        model(LEDGER, false);
        model(EMPLOYEE, true);
        model(ADDRESS, true);

        EndpointIndex index = mock(EndpointIndex.class);
        when(index.actionPermissions(anyString(), anyString())).thenReturn(Set.of());
        when(index.actionPermissions(QUOTA, "export")).thenReturn(Set.of(QUOTA_EXPORT));
        when(index.actionPermissions(QUOTA, "import")).thenReturn(Set.of(QUOTA_IMPORT));
        when(index.actionPermissions(EMPLOYEE, "export")).thenReturn(Set.of(EMPLOYEE_EXPORT));
        when(index.actionPermissions(EMPLOYEE, "import")).thenReturn(Set.of(EMPLOYEE_IMPORT));

        pi = new PermissionInfo();
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenAnswer(inv -> pi);
        service = new PermissionServiceImpl(provider, mock(ScopeRuleCompiler.class), null,
                mock(ModelService.class), mock(ScopeApplicabilityResolver.class), () -> index);
    }

    @AfterEach
    void tearDown() {
        models.close();
        SystemConfig.env = previousConfig;
    }

    @Test
    @DisplayName("a tenant admin exports and imports what its menus reach, and nothing else")
    void aTenantAdminIsAskedByItsSnapshot() {
        // A tenant admin's permissions are its menus: every tenant-facing page its plan includes.
        as(BuiltinRole.TENANT_ADMIN, EMPLOYEE_EXPORT, EMPLOYEE_IMPORT);

        assertThat(mayExport(EMPLOYEE)).isTrue();
        assertThat(mayImport(EMPLOYEE)).isTrue();
        assertThat(mayExport(QUOTA)).as("every tenant's quota").isFalse();
        assertThat(mayImport(QUOTA)).as("its own quota, raised").isFalse();
    }

    @Test
    @DisplayName("a shared model no permission names is refused to whoever reads past row scope")
    void anUnregisteredSharedModelIsRefusedPastRowScope() {
        for (BuiltinRole principal : new BuiltinRole[] {BuiltinRole.TENANT_ADMIN, BuiltinRole.CONSULTANT}) {
            as(principal, EMPLOYEE_EXPORT, EMPLOYEE_IMPORT);

            assertThat(mayExport(LEDGER)).as("%s exporting every tenant's rows", principal).isFalse();
            assertThat(mayImport(LEDGER)).as("%s importing into every tenant's table", principal).isFalse();
        }
    }

    @Test
    @DisplayName("a tenant-owned model no permission names stays open to them")
    void anUnregisteredTenantModelStaysGranted() {
        // The tenant filter still bounds it, and a child exported with its parent has no page of its own.
        for (BuiltinRole principal : new BuiltinRole[] {BuiltinRole.TENANT_ADMIN, BuiltinRole.CONSULTANT}) {
            as(principal, EMPLOYEE_EXPORT);

            assertThat(mayExport(ADDRESS)).as("%s", principal).isTrue();
            assertThat(mayImport(ADDRESS)).as("%s", principal).isTrue();
        }
    }

    @Test
    @DisplayName("an ordinary user is unchanged: row scope bounds what no permission names")
    void anOrdinaryUserIsUnchanged() {
        as(null, EMPLOYEE_EXPORT);

        assertThat(mayExport(EMPLOYEE)).isTrue();
        assertThat(mayExport(QUOTA)).isFalse();
        assertThat(mayExport(LEDGER)).isTrue();
    }

    @Test
    @DisplayName("the platform admin still passes on its role")
    void thePlatformAdminPasses() {
        as(BuiltinRole.SUPER_ADMIN);

        assertThat(mayExport(QUOTA)).isTrue();
        assertThat(mayImport(QUOTA)).isTrue();
        assertThat(mayExport(LEDGER)).isTrue();
        assertThat(mayImport(LEDGER)).isTrue();
    }

    @Test
    @DisplayName("without multi-tenancy no table is another tenant's")
    void withoutMultiTenancyNothingIsShared() {
        SystemConfig.env.setEnableMultiTenancy(false);
        as(BuiltinRole.TENANT_ADMIN, EMPLOYEE_EXPORT);

        assertThat(mayExport(LEDGER)).isTrue();
        assertThat(mayImport(LEDGER)).isTrue();
    }

    private void model(String name, boolean multiTenant) {
        MetaModel meta = mock(MetaModel.class);
        when(meta.isMultiTenant()).thenReturn(multiTenant);
        models.when(() -> ModelManager.existModel(name)).thenReturn(true);
        models.when(() -> ModelManager.getModel(name)).thenReturn(meta);
    }

    /** The caller: {@code role} (none = an ordinary role) holding {@code permissions}. */
    private void as(BuiltinRole role, String... permissions) {
        pi = new PermissionInfo();
        pi.setRoleCodes(role == null ? Set.of("HR") : Set.of(role.getCode()));
        pi.setPermissions(Set.of(permissions));
    }

    private boolean mayExport(String model) {
        return ContextHolder.callWith(context(), () -> service.hasModelExportGrant(model));
    }

    private boolean mayImport(String model) {
        return ContextHolder.callWith(context(), () -> service.hasModelImportGrant(model));
    }

    private static Context context() {
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);
        return ctx;
    }
}
