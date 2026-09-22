package io.softa.starter.user.service.impl;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.dto.ConsultantGrantDTO;
import io.softa.starter.user.entity.ConsultantAuthorization;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Authorized Tenants table shows the state of the membership each grant minted.
 *
 * <p>Entry needs two parties to agree — the platform authorizes, the customer's own administrator
 * may suspend the account — and only the first of those is visible from the grant. Without the
 * second, an operator reading "authorized until December" answers a consultant's "I cannot get in"
 * with "but you are authorized", and there is nothing on the screen that could correct them.
 *
 * <p>The grant names the membership it minted, so the status arrives with it as a cascaded field.
 * It used to be read separately and matched up in memory by {@code (profileId, tenantId)}; the
 * relation says the same thing to the database, which is why these cases assert on what the grant
 * carries and pin that the memberships are not read at all.
 */
class ConsultantGrantStatusTest {

    private static final Long PROFILE = 7L;

    private ConsultantServiceImpl service;
    private ConsultantAuthorizationService authorizationService;
    private UserAccountService accountService;
    private TenantInfoService tenantInfoService;

    @BeforeEach
    void setUp() {
        service = spy(new ConsultantServiceImpl());
        authorizationService = mock(ConsultantAuthorizationService.class);
        accountService = mock(UserAccountService.class);
        tenantInfoService = mock(TenantInfoService.class);
        ReflectionTestUtils.setField(service, "authorizationService", authorizationService);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "tenantInfoService", tenantInfoService);

        when(tenantInfoService.getTenantName(10L)).thenReturn("Acme");
        when(tenantInfoService.getTenantName(11L)).thenReturn("Beta");
        when(tenantInfoService.getTenantName(12L)).thenReturn("Gamma");
    }

    @Test
    void eachGrantCarriesItsMembershipStatus() {
        when(authorizationService.searchList(any(Filters.class))).thenReturn(List.of(
                grant(10L, LocalDate.of(2026, 12, 31), AccountStatus.ACTIVE),
                grant(11L, null, AccountStatus.FROZEN)));

        assertThat(service.grantsOf(PROFILE))
                .extracting(ConsultantGrantDTO::getTenantId, ConsultantGrantDTO::getTenantName,
                        ConsultantGrantDTO::getEndDate, ConsultantGrantDTO::getAccountStatus)
                .containsExactly(
                        tuple(10L, "Acme", LocalDate.of(2026, 12, 31), AccountStatus.ACTIVE),
                        // Authorized with no end date, and frozen by the customer anyway: the row
                        // the platform has no other way to learn about.
                        tuple(11L, "Beta", null, AccountStatus.FROZEN));
    }

    @Test
    void aGrantWithNoMembershipReportsNoStatusRatherThanAPlausibleOne() {
        when(authorizationService.searchList(any(Filters.class)))
                .thenReturn(List.of(grant(12L, null, null)));

        // Null is a real state: a grant whose account was never minted, or was removed out of band,
        // is exactly what an operator needs to see. Defaulting it to ACTIVE would hide the one row
        // that is actually broken behind the one reading that looks fine.
        assertThat(service.grantsOf(PROFILE))
                .singleElement()
                .extracting(ConsultantGrantDTO::getAccountStatus)
                .isNull();
    }

    @Test
    void theMembershipsAreNotReadAtAll() {
        when(authorizationService.searchList(any(Filters.class))).thenReturn(List.of(
                grant(10L, null, AccountStatus.ACTIVE),
                grant(11L, null, AccountStatus.ACTIVE),
                grant(12L, null, AccountStatus.ACTIVE)));

        service.grantsOf(PROFILE);

        // The grant names its membership, so the status rides along on the same read. Asserted as
        // "never" rather than as a count, because a count would also pass for a version that had
        // gone back to reading them and simply batched it.
        verify(accountService, never()).listMembershipsOf(anyLong());
        verify(accountService, never()).findMembershipInTenant(anyLong(), anyLong());
    }

    @Test
    void noGrantsReadsNothingAndReturnsNothing() {
        when(authorizationService.searchList(any(Filters.class))).thenReturn(List.of());

        assertThat(service.grantsOf(PROFILE)).isEmpty();
        verify(accountService, never()).listMembershipsOf(anyLong());
    }

    private static ConsultantAuthorization grant(Long tenantId, LocalDate end, AccountStatus status) {
        ConsultantAuthorization grant = new ConsultantAuthorization();
        grant.setId(tenantId);
        grant.setProfileId(PROFILE);
        grant.setTenantId(tenantId);
        grant.setEndDate(end);
        // What the cascaded field resolves to on a real read; null is "this grant minted nothing".
        grant.setAccountId(status == null ? null : tenantId * 100);
        grant.setAccountStatus(status);
        return grant;
    }
}
