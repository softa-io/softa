package io.softa.starter.user.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.security.EncryptUtils;
import io.softa.framework.base.utils.RandomUtils;
import io.softa.framework.orm.service.CacheService;

/**
 * The proof that a /join caller passed the verification code, carried across the two anonymous
 * steps that follow it.
 *
 * <p>POST /login/setJoinPassword and /login/confirmJoin have no session; what authorized them was
 * the invitation token plus a caller-supplied profileId. For a row already bound to a person the
 * profileId is the whole tie — and it is readable off the roster. A company holding a re-hired
 * person's work mailbox (where the link lands) could therefore skip the code entirely, call
 * setJoinPassword with the token and that person's id, set their GLOBAL password, and sign in as
 * them at every other company they belong to. The proof closes that: verifyJoinCode mints it only
 * once a code actually passed, and both later steps refuse without one naming THIS invitation and
 * THIS person.
 *
 * <p>Stored hashed, keyed by its own digest, the way the invitation token is: a cache dump then
 * yields nothing replayable. The value names the invitation by its token hash so a proof minted
 * for one link cannot be spent on another, and the profileId so it cannot be spent on another
 * person even under the same link. It lives 15 minutes — long enough to type a password and press
 * Join, short enough that an abandoned tab is not a standing credential — and confirmJoin consumes
 * it on success so the completed flow leaves nothing behind.
 */
@Component
@RequiredArgsConstructor
public class JoinProofGuard {

    /** Same entropy as the invitation token; it stands in for the code that was just passed. */
    private static final int PROOF_BYTES = 32;
    private static final int PROOF_TTL_SECONDS = 15 * 60;
    /** One wording for missing, expired, foreign-invitation and foreign-person proofs alike:
     *  distinguishing them would tell a caller probing profileIds which ones a proof exists for. */
    static final String REFUSAL = "Verify the code sent to your contact first.";

    private final CacheService cacheService;

    private static String key(String proof) {
        return "join:proof:" + EncryptUtils.computeSha256(proof);
    }

    private static String claim(String rawToken, Long profileId) {
        return EncryptUtils.computeSha256(rawToken) + "|" + profileId;
    }

    /** Mint a fresh proof that {@code profileId} passed the code for invitation {@code rawToken}. */
    public String mint(String rawToken, Long profileId) {
        return this.mint(rawToken, profileId, null);
    }

    /**
     * Mint a proof that also remembers which contact was verified ({@code mobile} / {@code email}),
     * so confirmJoin can prefill the login screen with it.
     */
    public String mint(String rawToken, Long profileId, String channel) {
        String proof = RandomUtils.randomString(PROOF_BYTES);
        cacheService.save(key(proof), claim(rawToken, profileId), PROOF_TTL_SECONDS);
        if (channel != null) {
            cacheService.save(channelKey(proof), channel, PROOF_TTL_SECONDS);
        }
        return proof;
    }

    /** The channel the proof was minted for, or null. */
    public String channelOf(String proof) {
        return StringUtils.isBlank(proof) ? null : cacheService.get(channelKey(proof));
    }

    private static String channelKey(String proof) {
        return "join:proof-channel:" + EncryptUtils.computeSha256(proof);
    }

    /**
     * Refuse unless {@code proof} is live and was minted for exactly this invitation and person.
     *
     * @throws BusinessException with {@link #REFUSAL} otherwise
     */
    public void require(String proof, String rawToken, Long profileId) {
        String held = StringUtils.isBlank(proof) || StringUtils.isBlank(rawToken) || profileId == null
                ? null : cacheService.get(key(proof));
        if (held == null || !held.equals(claim(rawToken, profileId))) {
            throw new BusinessException(REFUSAL);
        }
    }

    private static String joinPersonKey(String rawToken) {
        return "join:person:" + EncryptUtils.computeSha256(rawToken);
    }

    /**
     * Remember the person a verification of this link created. Until confirmJoin binds an account
     * to them they hold none, which is exactly what a dormant holder looks like; verifying again
     * must find them rather than release their number and make another person.
     */
    public void rememberJoinPerson(String rawToken, Long profileId) {
        if (StringUtils.isNotBlank(rawToken) && profileId != null) {
            cacheService.save(joinPersonKey(rawToken), profileId.toString(), PROOF_TTL_SECONDS);
        }
    }

    /** The person a verification of this link created, or null. */
    public Long joinPersonOf(String rawToken) {
        String value = StringUtils.isBlank(rawToken) ? null : cacheService.get(joinPersonKey(rawToken));
        return value == null ? null : Long.valueOf(value);
    }

    /** Spend the proof: the step it existed for has completed. */
    public void consume(String proof) {
        if (StringUtils.isNotBlank(proof)) {
            cacheService.clear(key(proof));
            cacheService.clear(channelKey(proof));
        }
    }
}
