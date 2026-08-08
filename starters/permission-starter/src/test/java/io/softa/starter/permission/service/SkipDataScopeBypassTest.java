package io.softa.starter.permission.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.sensitive.SensitiveFieldSetCache;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The narrow flag's contract: {@code skipDataScope} bypasses the TWO row-scope
 * entry points and nothing else — the point of adding it instead of reusing
 * {@code skipPermissionCheck} is that field-level protection stays on.
 */
class SkipDataScopeBypassTest {

    private final PermissionSnapshotProvider snapshotProvider = mock(PermissionSnapshotProvider.class);
    private final ScopeRuleCompiler scopeCompiler = mock(ScopeRuleCompiler.class);
    private final SensitiveFieldSetCache sfsCache = mock(SensitiveFieldSetCache.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);
    private final ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);

    private final PermissionServiceImpl service = new PermissionServiceImpl(
            snapshotProvider, scopeCompiler, sfsCache, modelService, applicability);

    private static Context ctx(boolean skipDataScope) {
        Context ctx = new Context();
        ctx.setUserId(7L);
        ctx.setTenantId(1L);
        ctx.setSkipDataScope(skipDataScope);
        return ctx;
    }

    @Test
    void appendScopeAccessFilters_bypassed_originalReturnedUntouched() {
        Filters original = new Filters();
        Filters out = ContextHolder.callWith(ctx(true),
                () -> service.appendScopeAccessFilters("LeaveBalanceAccount", original));
        assertThat(out).isSameAs(original);
        // bypass happens before any snapshot / scope machinery is consulted
        verifyNoInteractions(snapshotProvider, scopeCompiler, modelService);
    }

    @Test
    void checkIdsAccess_bypassed_noCountIssued() {
        ContextHolder.callWith(ctx(true), () -> {
            service.checkIdsAccess("LeaveBalanceAccount", List.of(1L, 2L), AccessType.UPDATE);
            return null;
        });
        verifyNoInteractions(snapshotProvider, modelService);
    }

    @Test
    void fieldMasking_NOT_bypassed_snapshotStillConsulted() {
        PermissionInfo pi = mock(PermissionInfo.class);
        when(pi.isAdmin()).thenReturn(false);
        when(snapshotProvider.get(1L, 7L)).thenReturn(pi);

        ContextHolder.callWith(ctx(true),
                () -> service.getUserBlockedModelFields("Employee", AccessType.READ));

        // the field-level path still resolves the caller's snapshot — the narrow
        // flag did not short-circuit it (skipPermissionCheck would have)
        verify(snapshotProvider).get(1L, 7L);
    }

    @Test
    void flagOff_scopePathRunsNormally() {
        PermissionInfo pi = mock(PermissionInfo.class);
        when(snapshotProvider.get(1L, 7L)).thenReturn(pi);
        when(pi.isAdmin()).thenReturn(true);   // admin → early return, keeps the test off the deep path

        Filters original = new Filters();
        Filters out = ContextHolder.callWith(ctx(false),
                () -> service.appendScopeAccessFilters("LeaveBalanceAccount", original));

        assertThat(out).isSameAs(original);
        verify(snapshotProvider).get(1L, 7L);  // NOT bypassed: snapshot was consulted
    }
}
