package io.softa.starter.permission.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.scope.ModelDefaultScopeRegistry;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which related models a relation expansion reads under the caller's row range when it reads more than
 * their labels.
 *
 * <p>Everything that would bound a model's rows before an owner is looked for answers true: the read
 * then goes the way a direct read goes. What is left — a model reachable only through the row that owns
 * it — answers false, and is read across the range, as visible as the parent row being expanded.
 */
class OwnRowScopeTest {

    private static final Set<ScopeType> UNIVERSAL_ONLY =
            Set.of(ScopeType.ALL, ScopeType.CUSTOM, ScopeType.CREATED_BY_SELF);
    private static final Set<ScopeType> ANCHORED =
            Set.of(ScopeType.ALL, ScopeType.CUSTOM, ScopeType.CREATED_BY_SELF, ScopeType.DEPT_SUBTREE);

    private MockedStatic<ModelManager> modelManager;
    private ScopeApplicabilityResolver applicability;
    private ModelDefaultScopeRegistry defaultScopes;

    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
        applicability = mock(ScopeApplicabilityResolver.class);
        defaultScopes = mock(ModelDefaultScopeRegistry.class);
        model("Employee", ANCHORED, false);
        model("EmployeeProfile", UNIVERSAL_ONLY, false);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void aModelWithAnAnchorHasItsOwnRange() {
        assertThat(ask(roleHolding("Employee"), "Employee")).isTrue();
    }

    @Test
    void anOwnedChildWithNoRuleOfItsOwnHasNone() {
        // The profile hangs off Employee one-to-one: nothing bounds it but the row that owns it.
        assertThat(ask(roleHolding("Employee"), "EmployeeProfile")).isFalse();
    }

    @Test
    void anAnchorlessModelARoleHoldsARuleForHasItsOwnRange() {
        // An administrator configured a range for it; crossing that would ignore what they set.
        assertThat(ask(roleHolding("Employee", "EmployeeProfile"), "EmployeeProfile")).isTrue();
    }

    @Test
    void anAnchorlessModelDeclaringAScopeHasItsOwnRange() {
        model("ImportHistory", UNIVERSAL_ONLY, false);
        when(defaultScopes.scopeFor("ImportHistory")).thenReturn(ScopeType.CREATED_BY_SELF);

        assertThat(ask(roleHolding("Employee"), "ImportHistory")).isTrue();
    }

    @Test
    void anAnchorlessModelOnTheCompanyAxisHasItsOwnRange() {
        model("CompanyPayrollAccount", UNIVERSAL_ONLY, true);

        assertThat(ask(roleHolding("Employee"), "CompanyPayrollAccount")).isTrue();
    }

    @Test
    void anUnknownModelKeepsTheRange() {
        assertThat(ask(roleHolding("Employee"), "NoSuchModel")).isTrue();
    }

    @Test
    void fullDataAccessHasNoRangeToApply() {
        PermissionInfo admin = roleHolding("Employee");
        admin.setRoleCodes(Set.of(PermissionInfo.CODE_TENANT_ADMIN));

        assertThat(ask(admin, "Employee")).isFalse();
    }

    @Test
    void aBypassedReadHasNoRangeToApply() {
        Context ctx = context();
        ctx.setSkipPermissionCheck(true);

        assertThat(ContextHolder.callWith(ctx, () -> service(roleHolding("Employee")).hasOwnRowScope("Employee")))
                .isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private void model(String name, Set<ScopeType> applicable, boolean multiCompany) {
        MetaModel meta = mock(MetaModel.class);
        when(meta.isMultiCompany()).thenReturn(multiCompany);
        modelManager.when(() -> ModelManager.existModel(name)).thenReturn(true);
        modelManager.when(() -> ModelManager.getModel(name)).thenReturn(meta);
        modelManager.when(() -> ModelManager.getModelFields(name)).thenReturn(List.of());
        when(applicability.applicableFor(name)).thenReturn(applicable);
    }

    private static PermissionInfo roleHolding(String... models) {
        PermissionInfo pi = new PermissionInfo();
        Map<String, List<ScopeRule>> scopes = new java.util.HashMap<>();
        for (String m : models) {
            ScopeRule rule = new ScopeRule();
            rule.setScopeType(ScopeType.ALL);
            scopes.put(m, List.of(rule));
        }
        pi.setModelScopeMap(scopes);
        return pi;
    }

    private PermissionServiceImpl service(PermissionInfo pi) {
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenReturn(pi);
        @SuppressWarnings("unchecked")
        ModelService<Long> modelService = mock(ModelService.class);
        return new PermissionServiceImpl(provider, mock(ScopeRuleCompiler.class), null, modelService,
                applicability, () -> null, () -> null, () -> defaultScopes);
    }

    private boolean ask(PermissionInfo pi, String model) {
        return ContextHolder.callWith(context(), () -> service(pi).hasOwnRowScope(model));
    }

    private static Context context() {
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(42L);
        return ctx;
    }
}
