package io.softa.framework.orm.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Timezone;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A company's "today" resolves in the order the a company's dates are judged in: the company's own
 * zone, else the tenant's default, else the server's.
 */
class CompanyClockTest {

    @Test
    void theCompanysOwnZoneComesFirst() {
        assertThat(CompanyClock.resolveZone("Pacific/Auckland", Timezone.values()[0]))
                .isEqualTo(ZoneId.of("Pacific/Auckland"));
    }

    @Test
    void theTenantsDefaultWhenTheCompanyHasNone() {
        Timezone tenant = Timezone.values()[0];
        assertThat(CompanyClock.resolveZone(null, tenant)).isEqualTo(tenant.toZoneId());
        assertThat(CompanyClock.resolveZone("  ", tenant)).isEqualTo(tenant.toZoneId());
    }

    @Test
    void anUnreadableCompanyZoneFallsThroughToTheTenants() {
        Timezone tenant = Timezone.values()[0];
        assertThat(CompanyClock.resolveZone("Not/AZone", tenant)).isEqualTo(tenant.toZoneId());
    }

    @Test
    void theServersZoneWhenNeitherIsSet() {
        assertThat(CompanyClock.resolveZone(null, null)).isEqualTo(ZoneId.systemDefault());
    }

    // ─── read from the stored company and the tenant ───

    private static final Timezone TENANT_ZONE = Timezone.values()[0];

    @SuppressWarnings("unchecked")
    private <T> T withClock(boolean companyHasTimeZone, java.util.function.Function<CompanyClock, T> ask) {
        ModelService<Long> models = mock(ModelService.class);
        when(models.getById(eq("Company"), eq(1L), any(Collection.class)))
                .thenReturn(Optional.of(Map.of("timeZone", Map.of("id", "Pacific/Auckland"))));
        when(models.getById(eq("Company"), eq(2L), any(Collection.class))).thenReturn(Optional.of(Map.of()));
        when(models.searchList(eq("Company"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("id", 1L, "timeZone", "Pacific/Auckland"), Map.of("id", 2L)));
        TenantInfoService tenants = mock(TenantInfoService.class);
        when(tenants.getDefaultTimezone(7L)).thenReturn(TENANT_ZONE);
        ObjectProvider<TenantInfoService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tenants);
        CompanyClock clock = new CompanyClock(models, provider);
        Context context = new Context();
        context.setTenantId(7L);
        try (MockedStatic<ModelManager> manager = mockStatic(ModelManager.class)) {
            manager.when(() -> ModelManager.existModel("Company")).thenReturn(true);
            manager.when(() -> ModelManager.getModelFieldOrNull("Company", "timeZone"))
                    .thenReturn(companyHasTimeZone ? mock(MetaField.class) : null);
            return ContextHolder.callWith(context, () -> ask.apply(clock));
        }
    }

    private ZoneId zoneOf(boolean companyHasTimeZone, Long companyId) {
        return withClock(companyHasTimeZone, clock -> clock.zoneOf(companyId));
    }

    @Test
    void aStoredCompanyIsReadInItsOwnZoneAndOneWithoutInTheTenants() {
        assertThat(zoneOf(true, 1L)).isEqualTo(ZoneId.of("Pacific/Auckland"));
        assertThat(zoneOf(true, 2L)).isEqualTo(TENANT_ZONE.toZoneId());
        assertThat(zoneOf(true, null)).isEqualTo(TENANT_ZONE.toZoneId());
    }

    @Test
    void anApplicationWhoseCompanyHasNoTimeZoneGetsTheTenantsCalendar() {
        assertThat(zoneOf(false, 1L)).isEqualTo(TENANT_ZONE.toZoneId());
        assertThat(withClock(false, CompanyClock::companiesByToday)).isEmpty();
    }

    @Test
    void companiesAreGroupedByTheDayItIsThereNow() {
        Map<LocalDate, Set<Long>> byToday = withClock(true, CompanyClock::companiesByToday);

        assertThat(byToday.values().stream().flatMap(Set::stream)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(byToday.get(LocalDate.now(ZoneId.of("Pacific/Auckland")))).contains(1L);
        assertThat(byToday.get(LocalDate.now(TENANT_ZONE.toZoneId()))).contains(2L);
    }
}
