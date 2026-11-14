package io.softa.starter.user.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.service.UserProfileService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Both updateOne overloads must evict the cached UserInfo (finding: the two-arg twin didn't).
 *
 * <p>The account row is the login gate's source of truth while the cached UserInfo is
 * ContextBuilder's; a write that changes one without dropping the other leaves them disagreeing for
 * the cache's month-long TTL. The single-arg override always evicted. The two-arg override —
 * reached by off-board, unbind, revive and reset, the writes that clear a column — did not, so an
 * UNBOUND account's session stayed alive and active. This pins both.
 */
class AccountCacheEvictionTest {

    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final UserAccountServiceImpl accountService = new UserAccountServiceImpl();
    private final io.softa.starter.user.service.UserSecurityLogService securityLog =
            AccountServiceTestSupport.quiet(accountService);

    AccountCacheEvictionTest() {
        ReflectionTestUtils.setField(accountService, "modelService", modelService);
        ReflectionTestUtils.setField(accountService, "profileService", profileService);
        when(modelService.updateOne(anyString(), any())).thenReturn(true);
    }

    private static UserAccount account() {
        UserAccount account = new UserAccount();
        account.setId(100L);
        return account;
    }

    @Test
    void singleArgUpdate_evicts() {
        accountService.updateOne(account());
        verify(profileService).evictUserInfo(100L);
    }

    @Test
    void twoArgUpdate_evicts_too() {
        // The one that was missing: unbind/reset/revive write through here to clear a column.
        accountService.updateOne(account(), false);
        verify(profileService).evictUserInfo(100L);
    }
}
