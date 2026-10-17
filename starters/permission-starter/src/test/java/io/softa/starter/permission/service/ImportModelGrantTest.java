package io.softa.starter.permission.service;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
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
 * The import of a model is held by the permission registered for that model's import — holding the
 * import of another model does not lend it.
 */
class ImportModelGrantTest {

    private static final String EMPLOYEE_IMPORT = "permission.employee.import";
    private static final String DEPARTMENT_IMPORT = "permission.department.import";

    private PermissionInfo pi;
    private EndpointIndex index;
    private PermissionServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pi = new PermissionInfo();
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenAnswer(inv -> pi);
        index = mock(EndpointIndex.class);
        when(index.actionPermissions("Employee", "import")).thenReturn(Set.of(EMPLOYEE_IMPORT));
        when(index.actionPermissions("Department", "import")).thenReturn(Set.of(DEPARTMENT_IMPORT));
        service = new PermissionServiceImpl(provider, mock(ScopeRuleCompiler.class), null,
                mock(ModelService.class), mock(ScopeApplicabilityResolver.class), () -> index);
    }

    private boolean mayImport(String model) {
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);
        return ContextHolder.callWith(ctx, () -> service.hasModelImportGrant(model));
    }

    @Test
    @DisplayName("the import of one model does not open another's")
    void anotherModelsImportDoesNotGrant() {
        pi.setPermissions(Set.of(DEPARTMENT_IMPORT));

        assertThat(mayImport("Department")).isTrue();
        assertThat(mayImport("Employee")).isFalse();
    }

    @Test
    @DisplayName("a model with no import permission registered stays importable")
    void anUnregisteredModelIsGranted() {
        pi.setPermissions(Set.of(DEPARTMENT_IMPORT));

        assertThat(mayImport("Position")).isTrue();
    }
}
