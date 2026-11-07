package io.softa.starter.user.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.config.LoginProtectionProperties;
import io.softa.starter.user.constant.AccountErrorCodes;
import io.softa.starter.user.util.ClientInfo;

/**
 * One budget per calling address for everything that tries a credential: sending a code, checking a
 * code, trying a password. The per-identifier limits stop guessing one person's code or password;
 * this stops one address spreading its tries over many people.
 */
@Component
@RequiredArgsConstructor
public class AddressAttemptLimiter {

    static final int WINDOW_SECONDS = 3600;

    private final CacheService cacheService;
    private final LoginProtectionProperties properties;

    /** Count one attempt from the calling address; refused once the hour's budget is spent. */
    public void count() {
        String ip = ClientInfo.current().ip();
        if (ip == null) {
            return;
        }
        Long attempts = cacheService.increment("login:address-attempts:" + ip, WINDOW_SECONDS);
        if (attempts != null && attempts > properties.getAttemptsPerAddressPerHour()) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.TOO_MANY_REQUESTS, "Too many attempts. Please try again later.");
        }
    }
}
