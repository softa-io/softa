package io.softa.starter.user.service.impl;

import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.enums.AccountStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Which company a platform message about a person is filed under and counted against: the one
 * company they have joined, or none when they have joined several — a sign-in code or a password
 * reset for someone in two companies is neither one's.
 */
class SoleJoinedTenantTest {

    private static final Long PERSON = 7L;

    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());

    private static UserAccount membership(long tenantId, AccountStatus status) {
        UserAccount account = new UserAccount();
        account.setTenantId(tenantId);
        account.setProfileId(PERSON);
        account.setStatus(status);
        return account;
    }

    private void memberships(UserAccount... accounts) {
        doReturn(List.of(accounts)).when(accountService).listMembershipsOf(PERSON);
    }

    @Test
    void theOneJoinedCompanyIsNamed_invitationsElsewhereDoNotCount() {
        memberships(membership(5L, AccountStatus.ACTIVE), membership(6L, AccountStatus.INVITED),
                membership(8L, AccountStatus.PENDING));

        assertThat(accountService.soleJoinedTenantOf(PERSON)).contains(5L);
    }

    @Test
    void frozenAndLockedAreJoined() {
        memberships(membership(5L, AccountStatus.FROZEN));
        assertThat(accountService.soleJoinedTenantOf(PERSON)).contains(5L);

        memberships(membership(5L, AccountStatus.FROZEN), membership(6L, AccountStatus.LOCKED));
        assertThat(accountService.soleJoinedTenantOf(PERSON)).isEmpty();
    }

    @Test
    void twoJoinedCompaniesNameNeither() {
        memberships(membership(5L, AccountStatus.ACTIVE), membership(6L, AccountStatus.ACTIVE));

        assertThat(accountService.soleJoinedTenantOf(PERSON)).isEmpty();
    }

    @Test
    void noJoinedCompanyNamesNone() {
        memberships(membership(6L, AccountStatus.INVITED));
        assertThat(accountService.soleJoinedTenantOf(PERSON)).isEmpty();

        assertThat(accountService.soleJoinedTenantOf(null)).isEmpty();
    }
}
