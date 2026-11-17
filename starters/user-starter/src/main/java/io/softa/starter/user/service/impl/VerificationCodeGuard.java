package io.softa.starter.user.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.constant.AccountErrorCodes;

/**
 * The abuse limits around one-time verification codes, in one place.
 *
 * <p>A code by itself proves nothing without these: a 6-digit code is 10^6 wide, so unlimited
 * attempts break it in minutes and unlimited sends turn the SMS bill into the attack. The five
 * rules are deliberately different in kind:
 *
 * <ul>
 *   <li><b>60s between sends</b> — stops accidental double-taps and cheap flooding;</li>
 *   <li><b>≤10 sends per channel per day</b> — bounds the cost of a determined flooder;</li>
 *   <li><b>5 wrong tries kills the code</b> — bounds guesses per issued code;</li>
 *   <li><b>10 consecutive wrong tries bans the channel for 30 minutes</b> — bounds guesses
 *       across codes, which the per-code limit alone would let a caller reset by re-sending;</li>
 *   <li><b>the ban gates sending too</b>, otherwise "request a new code" is the reset.</li>
 * </ul>
 *
 * <p><b>Counters are per identifier</b> (this phone number / this email), never per account: the
 * caller has not proven who they are yet, so an account-keyed counter would be both bypassable
 * (switch channel) and a denial-of-service vector against someone else's account.
 *
 * <p>Everything lives in the cache with its own TTL, so state expires on its own — nothing to
 * clean up, and a restart loses only in-flight attempt counts (fail-open on those is acceptable;
 * the daily cap and the ban, which bound real abuse, are the ones with long TTLs).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationCodeGuard {

    /** Codes are 6 digits and live 5 minutes. */
    public static final int CODE_LENGTH = 6;
    public static final int CODE_TTL_SECONDS = 300;

    /** One send per channel per minute. */
    private static final int SEND_INTERVAL_SECONDS = 60;
    /** At most 10 sends per channel per calendar day's worth of seconds. */
    private static final int DAILY_SEND_LIMIT = 10;
    private static final int DAY_SECONDS = 86400;
    /** A single code dies after this many wrong tries. */
    private static final int ATTEMPTS_PER_CODE = 5;
    /** Consecutive wrong tries across codes that trigger a channel ban. */
    private static final int ATTEMPTS_BEFORE_BAN = 10;
    private static final int BAN_SECONDS = 1800;

    private final CacheService cacheService;

    /** The per-address budget shared with password attempts; absent in a bare unit test. */
    @Autowired(required = false)
    private AddressAttemptLimiter addressLimiter;

    private void countAddress() {
        if (addressLimiter != null) {
            addressLimiter.count();
        }
    }

    private static String key(String suffix, String identifier) {
        return RedisConstant.VERIFICATION_CODE + suffix + ":" + identifier;
    }

    // ─────────────────────── sending ───────────────────────

    /**
     * Assert a code may be sent to this identifier, and record the send.
     *
     * <p>Checked in escalating order — ban, then interval, then daily cap — so the caller is told
     * the most restrictive thing standing in their way rather than the first one tripped.
     *
     * @throws BusinessException when banned, too soon, or over the daily cap
     */
    public void beforeSend(String identifier) {
        countAddress();
        requireNotBanned(identifier);

        String cooldownKey = key("cooldown", identifier);
        if (cacheService.get(cooldownKey) != null) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
        }
        // Increment BEFORE sending: an over-cap request must not consume the provider quota it
        // is about to be refused for.
        Long today = cacheService.increment(key("daily", identifier), DAY_SECONDS);
        if (today != null && today > DAILY_SEND_LIMIT) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
        }
        cacheService.save(cooldownKey, "1", SEND_INTERVAL_SECONDS);
    }

    /** Store a freshly issued code, resetting this code's attempt budget. */
    public void store(String identifier, String code) {
        cacheService.save(key("code", identifier), code, CODE_TTL_SECONDS);
        // A new code gets a full budget. The CONSECUTIVE counter is deliberately NOT reset —
        // resetting it here would make "request a new code" the way to clear a ban.
        cacheService.clear(key("attempts", identifier));
    }

    // ─────────────────────── verifying ───────────────────────

    /**
     * Verify a submitted code, applying the attempt limits.
     *
     * @throws BusinessException when missing, banned, expired, or wrong (the message distinguishes
     *         "wrong" from "this code is now dead, request a new one", because the remedies differ)
     */
    public void verify(String identifier, String submitted) {
        // No code guesses nothing, so it is refused before any counter: a client sending none — one still
        // sending it under its old name, say — cannot spend the attempt budget or bring on the ban.
        if (StringUtils.isBlank(submitted)) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.CODE_INCORRECT, "Please enter the verification code.");
        }
        countAddress();
        requireNotBanned(identifier);

        String expected = cacheService.get(key("code", identifier));
        if (expected == null) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.CODE_EXPIRED,
                    "This code has expired. Please request a new one.");
        }
        if (expected.equals(submitted)) {
            // Success clears both counters: the consecutive one exists to catch a run of
            // failures, and this run ended.
            cacheService.clear(key("code", identifier));
            cacheService.clear(key("attempts", identifier));
            cacheService.clear(key("consecutive", identifier));
            return;
        }

        Long consecutive = cacheService.increment(key("consecutive", identifier), BAN_SECONDS);
        if (consecutive != null && consecutive >= ATTEMPTS_BEFORE_BAN) {
            cacheService.save(key("ban", identifier), "1", BAN_SECONDS);
            cacheService.clear(key("code", identifier));
            log.warn("Verification temporarily banned for an identifier after {} consecutive failures.",
                    consecutive);
            throw AccountErrorCodes.refusal(AccountErrorCodes.CODE_BLOCKED,
                    "Too many attempts. Verification is temporarily unavailable — try again in 30 minutes.");
        }

        Long attempts = cacheService.increment(key("attempts", identifier), CODE_TTL_SECONDS);
        if (attempts != null && attempts >= ATTEMPTS_PER_CODE) {
            cacheService.clear(key("code", identifier));
            throw AccountErrorCodes.refusal(AccountErrorCodes.CODE_INVALIDATED,
                    "Too many incorrect attempts. This code is no longer valid — please request a new one.");
        }
        throw AccountErrorCodes.refusal(AccountErrorCodes.CODE_INCORRECT, "Incorrect code. Please try again.");
    }

    private void requireNotBanned(String identifier) {
        if (cacheService.get(key("ban", identifier)) != null) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.CODE_BLOCKED,
                    "Too many attempts. Verification is temporarily unavailable — try again in 30 minutes.");
        }
    }
}
