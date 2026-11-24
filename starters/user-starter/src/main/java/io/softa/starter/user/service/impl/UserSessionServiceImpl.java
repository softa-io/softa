package io.softa.starter.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import org.apache.commons.lang3.math.NumberUtils;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.framework.web.session.SessionStore;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserSessionService;

/**
 * Ends sessions for the account flows. Inside a transaction the sessions are ended once it commits:
 * ended before, they could be opened again in the moments the old password or binding still holds,
 * and a change that then rolled back would have signed people out for nothing.
 */
@Slf4j
@Service
public class UserSessionServiceImpl implements UserSessionService {

    /**
     * How long a person-wide revocation is remembered for the sign-in tokens it voids: longer than an
     * org-unavailable mark is kept ({@link SessionStore#TTL_SECONDS} at most), since a re-entry token is
     * offered only while its ended session's mark lasts. A mark the change failed to take back can then
     * offer no token the change does not refuse. The session's own age is no bound: a session slides,
     * and the sign-in behind it can be any age.
     */
    static final int CREDENTIAL_MARK_TTL_SECONDS = SessionStore.TTL_SECONDS + RedisConstant.ONE_DAY;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private CacheService cacheService;

    /** @Lazy: the account service ends sessions through this one (Reset User, unbind). */
    @Autowired
    @Lazy
    private UserAccountService accountService;

    @Override
    public String currentSessionId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return SessionStore.sessionIdOf(attributes.getRequest());
    }

    @Override
    public int revokeAccount(Long accountId, SessionRevokeReason reason) {
        return afterCommit(() -> sessionStore.revokeAccount(accountId, null, reason));
    }

    @Override
    public int revokePerson(Long profileId, String keepSessionId, SessionRevokeReason reason) {
        if (profileId == null) {
            return 0;
        }
        // Marked now and again at commit: a sign-in token issued in between must not outlive it.
        this.markCredentialsChanged(profileId);
        return afterCommit(() -> {
            this.markCredentialsChanged(profileId);
            int revoked = sessionStore.revokeAccounts(accountService.listAccountIdsOf(profileId), keepSessionId,
                    reason);
            this.clearOrgUnavailableMarks(profileId);
            return revoked;
        });
    }

    /**
     * An ended session's mark offers a token into the person's other companies; the change takes that offer
     * back too, rather than leave the token check alone to refuse it. After the live sessions are ended, and on
     * its own: a failure here leaves only marks whose tokens the credential mark already refuses, and must not
     * keep the sessions alive.
     */
    private void clearOrgUnavailableMarks(Long profileId) {
        try {
            sessionStore.clearOrgUnavailableMarks(profileId);
        } catch (RuntimeException e) {
            log.warn("Org-unavailable marks of profile {} not cleared after a credential change.", profileId, e);
        }
    }

    private void markCredentialsChanged(Long profileId) {
        cacheService.save(credentialMarkKey(profileId), String.valueOf(System.currentTimeMillis()),
                CREDENTIAL_MARK_TTL_SECONDS);
    }

    /**
     * Run a revocation now outside a transaction, or once the current one commits — then returning
     * 0, the count being unknown yet. A failure after commit is logged: the change it follows took
     * effect, and its caller has already been told so.
     */
    static int afterCommit(java.util.function.IntSupplier revoke) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return revoke.getAsInt();
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    revoke.getAsInt();
                } catch (RuntimeException e) {
                    log.error("Sessions not revoked after commit: {}", e.getMessage());
                }
            }
        });
        return 0;
    }

    @Override
    public boolean credentialsChangedSince(Long profileId, long issuedAtMillis) {
        if (profileId == null) {
            return false;
        }
        long changedAt = NumberUtils.toLong(cacheService.get(credentialMarkKey(profileId)), -1L);
        return changedAt >= issuedAtMillis;
    }

    private static String credentialMarkKey(Long profileId) {
        return "login:credentials-changed:" + profileId;
    }
}
