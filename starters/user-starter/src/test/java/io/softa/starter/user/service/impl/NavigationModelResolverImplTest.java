package io.softa.starter.user.service.impl;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.Navigation;
import io.softa.starter.user.enums.NavigationType;
import io.softa.starter.user.service.NavigationModelResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NavigationModelResolverImplTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ModelService modelService;
    private NavigationModelResolverImpl resolver;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        resolver = new NavigationModelResolverImpl(modelService);
    }

    private static Navigation nav(String id, NavigationType type, String model) {
        Navigation n = new Navigation();
        n.setId(id);
        n.setType(type);
        n.setModel(model);
        return n;
    }

    @SuppressWarnings("unchecked")
    private void primeWith(List<Navigation> rows) {
        when(modelService.searchList(eq("Navigation"), any(FlexQuery.class), eq(Navigation.class)))
                .thenReturn(rows);
        resolver.init();
    }

    @Test
    void findNavigation_knownId_returnsRow() {
        Navigation n = nav("hr.employee", NavigationType.MENU, "Employee");
        primeWith(List.of(n));
        assertThat(resolver.findNavigation("hr.employee")).isSameAs(n);
    }

    @Test
    void findNavigation_unknownId_returnsNull() {
        primeWith(List.of(nav("hr.employee", NavigationType.MENU, "Employee")));
        assertThat(resolver.findNavigation("missing")).isNull();
    }

    @Test
    void allNavigations_returnsAllLoaded() {
        Navigation a = nav("hr.employee", NavigationType.MENU, "Employee");
        Navigation b = nav("hr.department", NavigationType.MENU, "Department");
        primeWith(List.of(a, b));
        assertThat(resolver.allNavigations()).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void resolvePrimaryModel_menuWithModel_returnsModel() {
        primeWith(List.of(nav("hr.employee", NavigationType.MENU, "Employee")));
        assertThat(resolver.resolvePrimaryModel("hr.employee")).isEqualTo("Employee");
    }

    @Test
    void resolvePrimaryModel_groupType_returnsNull() {
        // GROUP entries carry no primary model — modelOf() strips them.
        primeWith(List.of(nav("hr.section", NavigationType.GROUP, "IgnoredEvenIfSet")));
        assertThat(resolver.resolvePrimaryModel("hr.section")).isNull();
    }

    @Test
    void resolvePrimaryModel_unknownId_returnsNull() {
        primeWith(List.of(nav("hr.employee", NavigationType.MENU, "Employee")));
        assertThat(resolver.resolvePrimaryModel("missing")).isNull();
    }

    @Test
    void modelOf_static_nullNav_returnsNull() {
        assertThat(NavigationModelResolver.modelOf(null)).isNull();
    }

    @Test
    void modelOf_static_menuWithNullModel_returnsNull() {
        // Pure-container MENU (no primary model).
        Navigation n = nav("hr.section-menu", NavigationType.MENU, null);
        assertThat(NavigationModelResolver.modelOf(n)).isNull();
    }

    @Test
    void init_swapsSnapshotAtomically() {
        // First load
        primeWith(List.of(nav("a", NavigationType.MENU, "A")));
        assertThat(resolver.findNavigation("a")).isNotNull();

        // Second load with different rows — snapshot replaced.
        primeWith(List.of(nav("b", NavigationType.MENU, "B")));
        assertThat(resolver.findNavigation("a")).isNull();
        assertThat(resolver.findNavigation("b")).isNotNull();
    }
}
