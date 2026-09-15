package io.softa.starter.permission.spi.support;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deriving the company axis from the role's data scope on the company model.
 *
 * <p>Worth its own class because of how this fails. Every wrong answer here is silent: resolve to
 * {@code null} where a configuration existed and every role reaches every company; resolve to an empty
 * set where none existed and every unconfigured role's screens go blank. Neither raises anything, and
 * the second looks like an outage rather than a permission change.
 *
 * <p>The specific fragility is the map key. The rules arrive keyed by the model name the administrator
 * configured, and this looks them up under {@link ModelConstant#COMPANY_MODEL} — so a scope stored
 * against any other spelling of the company model is not a smaller grant, it is <b>no grant</b>, which
 * is unrestricted. That is the same class of failure as the grant table this replaced (a
 * {@code RoleLegalEntity} → {@code RoleCompany} rename once silently dropped enforcement), which is why
 * the key and the materialising query are asserted rather than only the returned set.
 */
class CompanyGrantReadTest {

    private static final String COMPANY_MODEL = ModelConstant.COMPANY_MODEL;

    private MockedStatic<ModelManager> modelManager;
    private ModelService<Long> modelService;
    private ScopeRuleCompiler compiler;
    private DefaultPermissionSnapshotProvider provider;

    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel(COMPANY_MODEL)).thenReturn(true);
        modelService = mockModelService();
        compiler = mock(ScopeRuleCompiler.class);
        provider = new DefaultPermissionSnapshotProvider(null, modelService, null, () -> compiler,
                List.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private static ModelService<Long> mockModelService() {
        return mock(ModelService.class);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    /** A rule of the given type on the company model, as the snapshot read would have keyed it. */
    private static Map<String, List<ScopeRule>> scopeOn(String model, ScopeType... types) {
        List<ScopeRule> rules = new java.util.ArrayList<>();
        for (ScopeType t : types) {
            rules.add(new ScopeRule(t, null));
        }
        return Map.of(model, rules);
    }

    private void companiesMatching(Filters compiled, List<Map<String, Object>> rows) {
        when(compiler.compile(any(), eq(COMPANY_MODEL))).thenReturn(compiled);
        when(modelService.searchList(eq(COMPANY_MODEL), any(FlexQuery.class))).thenReturn(rows);
    }

    // ---- the three states ------------------------------------------------

    @Test
    void resolvesTheAxisFromTheCompanyModelsOwnScope() {
        // One configuration, two effects: the row that narrows which companies the switcher offers is
        // the row that bounds every model belonging to a company. Asserted on the query as well as the
        // result, so reading the wrong model or the wrong field fails here rather than in production.
        companiesMatching(Filters.of("country", Operator.EQUAL, "SG"),
                List.of(Map.of("id", 8712L), Map.of("id", 9001L)));

        Set<Long> granted = provider.readGrantedCompanyIds(scopeOn(COMPANY_MODEL, ScopeType.CUSTOM));

        assertThat(granted).containsExactlyInAnyOrder(8712L, 9001L);
        ArgumentCaptor<FlexQuery> captor = ArgumentCaptor.forClass(FlexQuery.class);
        verify(modelService).searchList(eq(COMPANY_MODEL), captor.capture());
        assertThat(captor.getValue().getFields()).containsExactly(ModelConstant.ID);
    }

    @Test
    void anAllRuleStaysUnrestrictedRatherThanMaterialisingEveryId() {
        // The compiler answers ALL with null, and that has to stay null all the way out. Materialising
        // the full id list would look identical today and diverge tomorrow: a legal entity created after
        // this snapshot was cached would be missing from a grant that is supposed to mean "no limit",
        // and the role would stop seeing a company nobody restricted it from.
        when(compiler.compile(any(), eq(COMPANY_MODEL))).thenReturn(null);

        assertThat(provider.readGrantedCompanyIds(scopeOn(COMPANY_MODEL, ScopeType.ALL))).isNull();
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class));
    }

    @Test
    void aScopeThatResolvesToNoCompanyMeansNoCompanyAtAll() {
        // Distinct from the case below. Something was configured and it selects nothing — a role written
        // to reach no company, which is what a self-service employee is. Fail-closed is right precisely
        // because it was configured, so this must be an empty set and not null.
        companiesMatching(Filters.of("country", Operator.EQUAL, "ZZ"), List.of());

        assertThat(provider.readGrantedCompanyIds(scopeOn(COMPANY_MODEL, ScopeType.CUSTOM)))
                .isNotNull()
                .isEmpty();
    }

    @Test
    void noRuleForTheCompanyModelMeansUnrestricted() {
        // Absence of configuration must stay unrestricted, or shipping the axis blanks every
        // pre-existing role's screens at once. Checked before compiling on purpose: the compiler answers
        // an empty rule list with match-none, which is the right answer for "every rule degraded" and
        // the wrong one for "nobody configured this".
        assertThat(provider.readGrantedCompanyIds(Map.of())).isNull();
        assertThat(provider.readGrantedCompanyIds(scopeOn("Employee", ScopeType.SELF))).isNull();
        verify(compiler, never()).compile(any(), anyString());
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class));
    }

    // ---- degradation ------------------------------------------------------

    @Test
    void anAbsentModelCostsNeitherAQueryNorAnException() {
        // An application without a company dimension — the framework's own demo apps. Same degradation
        // as CompanyCountryEnricher: no model, no narrowing, no noise. null rather than empty,
        // because "no company dimension" is no axis, while empty would blank every read in an app that
        // has no companies at all.
        modelManager.when(() -> ModelManager.existModel(COMPANY_MODEL)).thenReturn(false);

        assertThat(provider.readGrantedCompanyIds(scopeOn(COMPANY_MODEL, ScopeType.CUSTOM))).isNull();
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class));
    }

    @Test
    void skipsRowsWhoseIdIsNotANumber() {
        // A malformed row must not become a grant entry. Dropping one company is bad; letting a
        // non-numeric value through would produce a filter that matches nothing and empty the caller's
        // screens instead, which reads as an outage.
        companiesMatching(Filters.of("country", Operator.EQUAL, "SG"), java.util.Arrays.asList(
                java.util.Collections.singletonMap("id", null),
                Map.of("id", "not-a-number"),
                Map.of("id", 8712L)));

        assertThat(provider.readGrantedCompanyIds(scopeOn(COMPANY_MODEL, ScopeType.CUSTOM)))
                .containsExactly(8712L);
    }

    @Test
    void passesEveryRuleOnTheModelToTheCompiler() {
        // The rules of all of a user's roles arrive merged under one key, and the union across roles is
        // the compiler's OR-merge — not something to re-implement here. A role granting SG plus a role
        // granting NZ must reach both companies, so dropping to the first rule would silently narrow.
        companiesMatching(Filters.of("country", Operator.IN, List.of("SG", "NZ")), List.of(Map.of("id", 8712L)));

        provider.readGrantedCompanyIds(scopeOn(COMPANY_MODEL, ScopeType.CUSTOM, ScopeType.CUSTOM));

        ArgumentCaptor<List<ScopeRule>> captor = ArgumentCaptor.captor();
        verify(compiler).compile(captor.capture(), eq(COMPANY_MODEL));
        assertThat(captor.getValue()).hasSize(2);
    }
}
