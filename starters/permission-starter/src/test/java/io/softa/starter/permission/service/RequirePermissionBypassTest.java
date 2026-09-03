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
 * The bypass contract behind {@code @RequirePermission}: the aspect opens
 * {@code Context.skipPermissionCheck} once the entry check has passed, and that
 * single flag turns off EVERY layer for the endpoint body — the row-scope entry
 * points and the field-level guards alike, sensitive masking included.
 *
 * <p>That reach is the decision, not an oversight: a narrow row-scope-only flag
 * ({@code skipDataScope}) existed and was removed in favour of one honest flag.
 * {@link #fieldGuards_alsoBypassed_snapshotNotConsulted()} is the test that used
 * to assert the opposite — it now pins the current contract.
 */
class RequirePermissionBypassTest {

    private final PermissionSnapshotProvider snapshotProvider = mock(PermissionSnapshotProvider.class);
    private final ScopeRuleCompiler scopeCompiler = mock(ScopeRuleCompiler.class);
    private final SensitiveFieldSetCache sfsCache = mock(SensitiveFieldSetCache.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);
    private final ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);

    private final PermissionServiceImpl service = new PermissionServiceImpl(
            snapshotProvider, scopeCompiler, sfsCache, modelService, applicability);

    private static Context ctx(boolean skipPermissionCheck) {
        Context ctx = new Context();
        ctx.setUserId(7L);
        ctx.setTenantId(1L);
        ctx.setSkipPermissionCheck(skipPermissionCheck);
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
    void fieldGuards_alsoBypassed_snapshotNotConsulted() {
        // One flag, one reach: inside a @RequirePermission body the field-level
        // guards are off too. Whoever wants masking to survive an elevated call
        // must reintroduce a separate flag rather than weaken this assertion.
        ContextHolder.callWith(ctx(true),
                () -> service.getUserBlockedModelFields("Employee", AccessType.READ));

        verifyNoInteractions(snapshotProvider);
    }

    @Test
    void flagOff_scopePathRunsNormally() {
        PermissionInfo pi = mock(PermissionInfo.class);
        when(snapshotProvider.get(1L, 7L)).thenReturn(pi);
        // The row-scope path now asks hasFullDataAccess (admins OR a consultant), so that is what
        // has to be stubbed for the early return this test relies on to keep off the deep path.
        when(pi.hasFullDataAccess()).thenReturn(true);

        Filters original = new Filters();
        Filters out = ContextHolder.callWith(ctx(false),
                () -> service.appendScopeAccessFilters("LeaveBalanceAccount", original));

        assertThat(out).isSameAs(original);
        verify(snapshotProvider).get(1L, 7L);  // NOT bypassed: snapshot was consulted
    }
}
