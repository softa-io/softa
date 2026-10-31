package io.softa.starter.user.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.framework.web.session.SessionStore;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserSessionService;

@Service
public class UserSessionServiceImpl implements UserSessionService {

    @Autowired
    private SessionStore sessionStore;

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
        return sessionStore.revokeAccounts(accountService.listAccountIdsOf(profileId), keepSessionId, reason);
    }
}
