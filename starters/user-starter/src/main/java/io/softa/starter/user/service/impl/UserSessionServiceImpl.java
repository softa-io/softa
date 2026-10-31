package io.softa.starter.user.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import org.apache.commons.lang3.math.NumberUtils;

import io.softa.framework.orm.service.CacheService;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.framework.web.session.SessionStore;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserSessionService;

@Service
public class UserSessionServiceImpl implements UserSessionService {

    /**
     * How long a person-wide revocation is remembered for the sign-in tokens it voids: longer than
     * any such token lives, so none issued before it can outlast the mark.
     */
    static final int CREDENTIAL_MARK_TTL_SECONDS = 15 * 60;

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
        return sessionStore.revokeAccount(accountId, null, reason);
    }

    @Override
    public int revokePerson(Long profileId, String keepSessionId, SessionRevokeReason reason) {
        if (profileId == null) {
            return 0;
        }
        cacheService.save(credentialMarkKey(profileId), String.valueOf(System.currentTimeMillis()),
                CREDENTIAL_MARK_TTL_SECONDS);
        return sessionStore.revokeAccounts(accountService.listAccountIdsOf(profileId), keepSessionId, reason);
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
