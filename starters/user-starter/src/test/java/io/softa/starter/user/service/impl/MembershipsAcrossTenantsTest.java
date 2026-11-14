package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserIdentityService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whether a person still works somewhere is a question about EVERY company, asked from inside one.
 *
 * <p>Nothing here stubs the membership lookup: the query itself runs against a stand-in for the ORM
 * that does what the real one does — filters by the caller's tenant unless the context says
 * cross-tenant. {@code @CrossTenant} on the lookup is an aspect, and the account service calls the
 * lookup on itself, past the proxy; this is what catches the annotation being skipped. Through that
 * gap, company B creating an account (or merely pre-checking an import row) saw company A's active
 * employee as having left everywhere, and released their login mobile.
 */
class MembershipsAcrossTenantsTest {

    private static final Long COMPANY_A = 1L;
    private static final Long COMPANY_B = 2L;
    private static final Long ADA = 7L;
    private static final String NUMBER = "+6591234567";

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    private final UserIdentity ada = new UserIdentity();

    MembershipsAcrossTenantsTest() {
        AccountServiceTestSupport.quiet(accountService);
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        ada.setId(11L);
        ada.setProfileId(ADA);
        ada.setLoginMobile(NUMBER);
        when(identityService.findByLoginIdentifier(NUMBER)).thenReturn(Optional.of(ada));

        UserAccount employedAtA = new UserAccount();
        employedAtA.setId(100L);
        employedAtA.setTenantId(COMPANY_A);
        employedAtA.setProfileId(ADA);
        employedAtA.setStatus(AccountStatus.ACTIVE);
        // The ORM's tenant filter, as the real one applies it.
        doAnswer(invocation -> {
            String filters = String.valueOf(invocation.<Filters>getArgument(0));
            if (!filters.contains("\"profileId\"")) {
                return List.of();
            }
            Context context = ContextHolder.getContext();
            boolean visible = context.isCrossTenant() || COMPANY_A.equals(context.getTenantId());
            return visible ? List.of(employedAtA) : List.of();
        }).when(accountService).searchList(any(Filters.class));
    }

    private static void inCompanyB(Runnable action) {
        Context context = new Context();
        context.setTenantId(COMPANY_B);
        ContextHolder.runWith(context, action);
    }

    @Test
    void fromAnotherCompany_anActiveEmployeesNumberIsNotDormant() {
        inCompanyB(() -> assertThat(accountService.releaseDormantLoginMobile(NUMBER, "Account created")).isFalse());

        assertThat(ada.getLoginMobile()).isEqualTo(NUMBER);
        verify(identityService, never()).updateLoginMobile(any(), any());
    }

    @Test
    void fromAnotherCompany_theMembershipsOfAPersonAreAllOfThem() {
        inCompanyB(() -> assertThat(accountService.listMembershipsOf(ADA)).hasSize(1));
        inCompanyB(() -> assertThat(accountService.listAccountIdsOf(ADA)).containsExactly(100L));
    }
}
