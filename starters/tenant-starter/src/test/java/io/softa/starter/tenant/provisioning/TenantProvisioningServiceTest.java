package io.softa.starter.tenant.provisioning;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import io.softa.framework.base.enums.Timezone;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.entity.TenantSubscription;
import io.softa.starter.tenant.enums.TenantLifecycle;
import io.softa.starter.tenant.service.TenantSubscriptionService;
import io.softa.starter.tenant.service.impl.TenantInfoServiceImpl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantProvisioningService#reconcileScheduledStart} — the inline-edit path's
 * two-way reconcile: an active sub with a future start → SCHEDULED (defer), and a SCHEDULED sub whose
 * start has arrived → SUBSCRIBED (activate). Dates use ±2-day margins so assertions are timezone-independent.
 */
class TenantProvisioningServiceTest {

    private static final long TENANT = 1L;
    private static final long SUB = 9L;

    private TenantInfoServiceImpl tenantInfoService;
    private TenantSubscriptionService subscriptionService;
    private TenantProvisioningService service;

    @BeforeEach
    void setUp() {
        tenantInfoService = mock(TenantInfoServiceImpl.class);
        subscriptionService = mock(TenantSubscriptionService.class);
        service = new TenantProvisioningService(tenantInfoService, subscriptionService,
                mock(ModelService.class), mock(ApplicationEventPublisher.class));
    }

    @Test
    void futureStart_parksActiveSubAsScheduled() {
        tenantHasSub();
        when(subscriptionService.getById(SUB)).thenReturn(Optional.of(
                sub(TenantLifecycle.SUBSCRIBED, LocalDate.now().plusDays(2))));

        service.reconcileScheduledStart(TENANT);

        verify(subscriptionService).updateOne(argThat(s -> s.getLifecycle() == TenantLifecycle.SCHEDULED));
    }

    @Test
    void pastStart_leavesActive() {
        tenantHasSub();
        when(subscriptionService.getById(SUB)).thenReturn(Optional.of(
                sub(TenantLifecycle.SUBSCRIBED, LocalDate.now().minusDays(2))));

        service.reconcileScheduledStart(TENANT);

        verify(subscriptionService, never()).updateOne(any());
    }

    @Test
    void nonActiveSub_notRescheduled() {
        tenantHasSub();
        when(subscriptionService.getById(SUB)).thenReturn(Optional.of(
                sub(TenantLifecycle.EXPIRED, LocalDate.now().plusDays(2))));

        service.reconcileScheduledStart(TENANT);

        verify(subscriptionService, never()).updateOne(any());
    }

    @Test
    void scheduledStartArrived_activatesToSubscribed() {
        tenantHasSub();
        when(subscriptionService.getById(SUB)).thenReturn(Optional.of(
                sub(TenantLifecycle.SCHEDULED, LocalDate.now().minusDays(2))));

        service.reconcileScheduledStart(TENANT);

        verify(subscriptionService).updateOne(argThat(s -> s.getLifecycle() == TenantLifecycle.SUBSCRIBED));
    }

    @Test
    void scheduledFutureStart_staysScheduled() {
        tenantHasSub();
        when(subscriptionService.getById(SUB)).thenReturn(Optional.of(
                sub(TenantLifecycle.SCHEDULED, LocalDate.now().plusDays(2))));

        service.reconcileScheduledStart(TENANT);

        verify(subscriptionService, never()).updateOne(any());
    }

    @Test
    void noSubscription_noOp() {
        TenantInfo t = new TenantInfo();
        t.setId(TENANT);
        t.setSubscriptionId(null);
        when(tenantInfoService.getById(TENANT)).thenReturn(Optional.of(t));

        service.reconcileScheduledStart(TENANT);

        verify(subscriptionService, never()).updateOne(any());
    }

    private void tenantHasSub() {
        TenantInfo t = new TenantInfo();
        t.setId(TENANT);
        t.setSubscriptionId(SUB);
        t.setDefaultTimezone(Timezone.UTC_P_08_00);
        when(tenantInfoService.getById(TENANT)).thenReturn(Optional.of(t));
    }

    private static TenantSubscription sub(TenantLifecycle lifecycle, LocalDate effectiveFrom) {
        TenantSubscription s = new TenantSubscription();
        s.setId(SUB);
        s.setLifecycle(lifecycle);
        s.setEffectiveFrom(effectiveFrom);
        return s;
    }
}
