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
import io.softa.starter.user.entity.UserAccount;
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
        when(authorizationService.searchList(any(Filters.class)))
                .thenReturn(List.of(grant(10L, LocalDate.of(2026, 12, 31)), grant(11L, null)));
        when(accountService.listMembershipsOf(PROFILE))
                .thenReturn(List.of(account(10L, AccountStatus.ACTIVE), account(11L, AccountStatus.FROZEN)));

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
                .thenReturn(List.of(grant(12L, null)));
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of());

        // Null is a real state: a grant whose account was never minted, or was removed out of band,
        // is exactly what an operator needs to see. Defaulting it to ACTIVE would hide the one row
        // that is actually broken behind the one reading that looks fine.
        assertThat(service.grantsOf(PROFILE))
                .singleElement()
                .extracting(ConsultantGrantDTO::getAccountStatus)
                .isNull();
    }

    @Test
    void theMembershipsAreReadOnceForThePage_notOncePerGrant() {
        when(authorizationService.searchList(any(Filters.class)))
                .thenReturn(List.of(grant(10L, null), grant(11L, null), grant(12L, null)));
        when(accountService.listMembershipsOf(PROFILE))
                .thenReturn(List.of(account(10L, AccountStatus.ACTIVE), account(11L, AccountStatus.ACTIVE),
                        account(12L, AccountStatus.ACTIVE)));

        service.grantsOf(PROFILE);

        verify(accountService).listMembershipsOf(PROFILE);
        // The per-grant lookup this replaces. Asserted as "never" rather than as a count, because a
        // count would also pass for a version that batched two of the three reads and not the third.
        verify(accountService, never()).findMembershipInTenant(anyLong(), anyLong());
    }

    @Test
    void noGrantsReadsNoMembershipsAtAll() {
        when(authorizationService.searchList(any(Filters.class))).thenReturn(List.of());

        assertThat(service.grantsOf(PROFILE)).isEmpty();
        verify(accountService, never()).listMembershipsOf(anyLong());
    }

    private static ConsultantAuthorization grant(Long tenantId, LocalDate end) {
        ConsultantAuthorization grant = new ConsultantAuthorization();
        grant.setId(tenantId);
        grant.setProfileId(PROFILE);
        grant.setTenantId(tenantId);
        grant.setEndDate(end);
        return grant;
    }

    private static UserAccount account(Long tenantId, AccountStatus status) {
        UserAccount account = new UserAccount();
        account.setId(tenantId * 100);
        account.setProfileId(PROFILE);
        account.setTenantId(tenantId);
        account.setConsultant(Boolean.TRUE);
        account.setStatus(status);
        return account;
    }
}
