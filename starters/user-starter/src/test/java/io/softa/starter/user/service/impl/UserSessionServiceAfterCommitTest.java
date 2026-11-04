package io.softa.starter.user.service.impl;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.orm.service.CacheService;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.framework.web.session.SessionStore;
import io.softa.starter.user.service.UserAccountService;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Inside a transaction, sessions end once it commits: ended before, they could be opened again while
 * the old password or binding still holds, and a rollback would have signed people out for nothing.
 */
class UserSessionServiceAfterCommitTest {

    private final SessionStore store = mock(SessionStore.class);
    private final CacheService cache = mock(CacheService.class);
    private final UserAccountService accounts = mock(UserAccountService.class);
    private final UserSessionServiceImpl service = new UserSessionServiceImpl();

    UserSessionServiceAfterCommitTest() {
        ReflectionTestUtils.setField(service, "sessionStore", store);
        ReflectionTestUtils.setField(service, "cacheService", cache);
        ReflectionTestUtils.setField(service, "accountService", accounts);
        when(accounts.listAccountIdsOf(7L)).thenReturn(List.of(100L));
    }

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    @Test
    void outsideATransaction_sessionsEndAtOnce() {
        service.revokeAccount(100L, SessionRevokeReason.ORG_UNAVAILABLE);

        verify(store).revokeAccount(100L, null, SessionRevokeReason.ORG_UNAVAILABLE);
    }

    @Test
    void insideATransaction_sessionsEndOnlyOnCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.revokeAccount(100L, SessionRevokeReason.UNBOUND);
        service.revokePerson(7L, "keep", SessionRevokeReason.PASSWORD_RESET);
        verify(store, never()).revokeAccount(any(), any(), any());
        verify(store, never()).revokeAccounts(any(), any(), any());
        // The sign-in tokens are voided at once, and again at commit.
        verify(cache).save(eq("login:credentials-changed:7"), anyString(), anyInt());

        commit();
        verify(store).revokeAccount(100L, null, SessionRevokeReason.UNBOUND);
        verify(store).revokeAccounts(List.of(100L), "keep", SessionRevokeReason.PASSWORD_RESET);
        verify(cache, times(2)).save(eq("login:credentials-changed:7"), anyString(), anyInt());
    }

    @Test
    void aFailureAfterCommit_isLogged_notThrownIntoTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        doThrow(new IllegalStateException("redis down")).when(store).revokeAccount(any(), isNull(), any());
        service.revokeAccount(100L, SessionRevokeReason.ORG_UNAVAILABLE);

        assertThatCode(UserSessionServiceAfterCommitTest::commit).doesNotThrowAnyException();
    }
}
