package io.softa.starter.permission.spi.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;

/**
 * "My countries" (PRD #1041) — the countries behind the company grant, read beside it.
 *
 * <p>The failure modes are the silent kind again. Resolve an unrestricted grant to {@code null} and
 * nothing narrows for administrators, who then see every seeded country's value domains in an SG-only
 * tenant; resolve an empty grant by querying and a role meant to reach no company gets every country.
 * Neither raises anything, so each state is pinned here, and the query is asserted as well as the
 * result — reading the wrong field yields an empty set that looks like a tenant with no countries.
 */
class CompanyCountriesReadTest {

    private static final String COMPANY_MODEL = ModelConstant.COMPANY_MODEL;

    private MockedStatic<ModelManager> modelManager;
    private ModelService<Long> modelService;
    private DefaultPermissionSnapshotProvider provider;

    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel(COMPANY_MODEL)).thenReturn(true);
        modelService = mockModelService();
        // Sixth argument: the shared-nav prefixes. Empty here — this suite is about the company
        // country read, and no nav is shared in it.
        provider = new DefaultPermissionSnapshotProvider(null, modelService, null, null,
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

    private void companiesReturn(List<Map<String, Object>> rows) {
        when(modelService.searchList(eq(COMPANY_MODEL), any(FlexQuery.class))).thenReturn(rows);
    }

    private FlexQuery capturedQuery() {
        ArgumentCaptor<FlexQuery> captor = ArgumentCaptor.forClass(FlexQuery.class);
        verify(modelService).searchList(eq(COMPANY_MODEL), captor.capture());
        return captor.getValue();
    }

    @Test
    void grantedIdsResolveToTheirCountries_deduplicated() {
        companiesReturn(List.of(Map.of("country", "SG"), Map.of("country", "SG"), Map.of("country", "NZ")));

        Set<String> countries = provider.readGrantedCountries(Set.of(1L, 2L, 3L));

        assertThat(countries).containsExactlyInAnyOrder("SG", "NZ");
        FlexQuery query = capturedQuery();
        assertThat(query.getFields()).containsExactly(ModelConstant.COUNTRY_FIELD);
        // Bounded to the grant: the ids, not the whole tenant.
        assertThat(query.getFilters().toString()).contains(ModelConstant.ID);
    }

    @Test
    void anUnrestrictedGrantResolvesToEveryCompanysCountry_notToNull() {
        // null ids = "no company axis configured". For countries that must still be a concrete set: an
        // administrator of an SG-only tenant works in SG, whatever a value domain was seeded for.
        companiesReturn(List.of(Map.of("country", "SG")));

        Set<String> countries = provider.readGrantedCountries(null);

        assertThat(countries).containsExactly("SG");
        FlexQuery query = capturedQuery();
        assertThat(query.getFields()).containsExactly(ModelConstant.COUNTRY_FIELD);
        assertThat(Filters.isEmpty(query.getFilters()))
                .as("unrestricted → no id bound on the query").isTrue();
    }

    @Test
    void noCompanyMeansNoCountry_withoutAQuery() {
        assertThat(provider.readGrantedCountries(Set.of())).isEmpty();
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class));
    }

    @Test
    void skipsCompaniesWithoutACountry() {
        Map<String, Object> blank = new java.util.HashMap<>();
        blank.put("country", null);
        companiesReturn(List.of(blank, Map.of("country", " NZ "), Map.of("country", "")));

        assertThat(provider.readGrantedCountries(Set.of(7L))).containsExactly("NZ");
    }

    @Test
    void anAbsentModelCostsNeitherAQueryNorAnException() {
        modelManager.when(() -> ModelManager.existModel(COMPANY_MODEL)).thenReturn(false);

        assertThat(provider.readGrantedCountries(null)).isNull();
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class));
    }
}
