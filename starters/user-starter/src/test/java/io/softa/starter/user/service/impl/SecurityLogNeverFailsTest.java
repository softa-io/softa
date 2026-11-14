package io.softa.starter.user.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.enums.SecurityAction;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A security-log row is evidence about a change, not part of it: whatever happens to the row, the
 * change it records goes on. The row has a transaction of its own, and the failure must be caught
 * outside it — caught inside, a failed insert leaves that transaction rollback-only and its commit
 * throws at the caller, after a password reset has already ended the person's sessions.
 */
class SecurityLogNeverFailsTest {

    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final UserSecurityLogServiceImpl securityLog = spy(new UserSecurityLogServiceImpl());

    SecurityLogNeverFailsTest() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        securityLog.setTransactionManager(transactions);
    }

    private static UserSecurityLog row() {
        UserSecurityLog row = new UserSecurityLog();
        row.setAction(SecurityAction.PASSWORD_RESET);
        return row;
    }

    @Test
    void theRowIsWrittenInATransactionOfItsOwn() {
        org.mockito.Mockito.doReturn(1L).when(securityLog).createOne(any(UserSecurityLog.class));

        securityLog.record(row());

        verify(transactions).getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void aFailedInsert_doesNotReachTheCaller() {
        doThrow(new IllegalStateException("insert failed")).when(securityLog).createOne(any(UserSecurityLog.class));

        assertThatCode(() -> securityLog.record(row())).doesNotThrowAnyException();
    }

    @Test
    void aCommitThatRollsBack_doesNotReachTheCallerEither() {
        org.mockito.Mockito.doReturn(1L).when(securityLog).createOne(any(UserSecurityLog.class));
        doThrow(new UnexpectedRollbackException("rolled back")).when(transactions).commit(any());

        assertThatCode(() -> securityLog.record(row())).doesNotThrowAnyException();
    }
}
