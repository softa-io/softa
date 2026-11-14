package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.enums.AccountStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * When HR saves a work email another company's person uses: refused for an account its person has
 * joined, let through for one nobody has joined yet — /join decides that one.
 */
class EnterpriseEmailRefusalTest {

    private static final Long ACCOUNT = 100L;
    private static final String EMAIL = "ada@acme.com";

    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    private final UserAccount account = new UserAccount();
    /** The other company's account using the address. */
    private final UserAccount elsewhere = new UserAccount();

    EnterpriseEmailRefusalTest() {
        account.setId(ACCOUNT);
        account.setTenantId(1L);
        account.setProfileId(7L);
        elsewhere.setId(200L);
        elsewhere.setTenantId(2L);
        elsewhere.setProfileId(8L);
        elsewhere.setEmail(EMAIL);
        elsewhere.setStatus(AccountStatus.ACTIVE);
        doReturn(Optional.of(account)).when(accountService).getById(ACCOUNT);
        // Nothing in this company holds it; across companies the other person's account does.
        doReturn(List.of()).when(accountService)
                .searchList(argThat((Filters f) -> f != null && f.toString().contains("tenantId")));
        doReturn(List.of(elsewhere)).when(accountService)
                .searchList(argThat((Filters f) -> f != null && !f.toString().contains("tenantId")));
    }

    @Test
    void aJoinedAccountIsRefused() {
        account.setStatus(AccountStatus.ACTIVE);

        assertThat(accountService.enterpriseEmailRefusal(ACCOUNT, EMAIL))
                .isEqualTo("This work email is already used by another user.");
    }

    @Test
    void anAccountNobodyHasJoinedYetIsLetThrough() {
        // It may be the same person, employed in the other company too; the number verified at
        // /join says whether it is.
        for (AccountStatus notYet : List.of(AccountStatus.PENDING, AccountStatus.INVITED)) {
            account.setStatus(notYet);
            assertThat(accountService.enterpriseEmailRefusal(ACCOUNT, EMAIL)).isNull();
        }
    }

    @Test
    void onlyAJoinedHolderCounts() {
        // A Pending account elsewhere names the placeholder minted with it, nobody yet.
        account.setStatus(AccountStatus.ACTIVE);
        elsewhere.setStatus(AccountStatus.PENDING);

        assertThat(accountService.enterpriseEmailRefusal(ACCOUNT, EMAIL)).isNull();
    }

    @Test
    void anotherAccountOfThisCompanyIsRefusedWhateverTheStatus() {
        account.setStatus(AccountStatus.PENDING);
        UserAccount colleague = new UserAccount();
        colleague.setId(300L);
        colleague.setTenantId(1L);
        colleague.setEmail(EMAIL);
        doReturn(List.of(colleague)).when(accountService)
                .searchList(argThat((Filters f) -> f != null && f.toString().contains("tenantId")));

        assertThat(accountService.enterpriseEmailRefusal(ACCOUNT, EMAIL))
                .isEqualTo("This work email is already used by another user.");
    }
}
