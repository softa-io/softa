package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.security.HashUtils;
import io.softa.framework.base.security.PasswordUtils;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.constant.AccountErrorCodes;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.util.LoginIdentifiers;

/**
 * {@link UserIdentityService} — the person's credentials, stored and verified here.
 *
 * <p>{@code @CrossTenant} on the lookups: a login identifier is global by definition, and the caller
 * has no tenant context yet — that is decided AFTER authenticating. Scoping these to a tenant would
 * make "who is this?" answerable only once you already knew. The annotation is entered through the
 * proxy on the public method, so the nested query below is covered by it too.
 *
 * <p>Injects only the cache, for the password-failure counter. The persistence comes from
 * {@code EntityServiceImpl}, and {@code requireIdentity} takes the account object rather than
 * looking it up — see the interface for why that matters (a bean cycle with
 * {@code UserAccountService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserIdentityServiceImpl extends EntityServiceImpl<UserIdentity, Long>
        implements UserIdentityService {

    /** Consecutive wrong passwords that lock the password path. */
    /** How long it stays locked. */
    private static final int LOCK_MINUTES = 30;

    private static final String ID = LambdaUtils.getAttributeName(UserIdentity::getId);
    private static final String PASSWORD = LambdaUtils.getAttributeName(UserIdentity::getPassword);
    private static final String PASSWORD_SALT = LambdaUtils.getAttributeName(UserIdentity::getPasswordSalt);
    private static final String PASSWORD_LOCKED_UNTIL =
            LambdaUtils.getAttributeName(UserIdentity::getPasswordLockedUntil);
    private static final String LOGIN_MOBILE = LambdaUtils.getAttributeName(UserIdentity::getLoginMobile);

    private final CacheService cacheService;

    /** @Lazy: the account service reads credentials through this one. */
    @Autowired
    @Lazy
    private UserAccountService accountService;

    /** @Lazy for the same reason; only asked whether a person is a consultant. */
    @Autowired
    @Lazy
    private ConsultantService consultantService;

    private static String failureKey(Long identityId) {
        return "login:pwd-failures:" + identityId;
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public UserIdentity requireIdentity(UserAccount account) {
        if (account == null) {
            throw new BusinessException("Account not found.");
        }
        Long profileId = account.getProfileId();
        if (profileId == null) {
            // After the migration this is a data fault, not a normal state. Treating it as "no
            // password" would let a password-less path through, so refuse loudly instead.
            throw new BusinessException("This account is not linked to a person yet — contact support.");
        }
        return this.findByProfile(profileId)
                .orElseThrow(() -> new BusinessException("Credentials not found for this account."));
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public Optional<UserIdentity> findByProfile(Long profileId) {
        if (profileId == null) {
            return Optional.empty();
        }
        return this.searchOne(new Filters().eq(UserIdentity::getProfileId, profileId));
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public Optional<UserIdentity> findByLoginIdentifier(String identifier) {
        List<String> mobileSpellings = LoginIdentifiers.loginSpellings(identifier);
        identifier = LoginIdentifiers.normalize(identifier);
        if (identifier == null) {
            return Optional.empty();
        }
        if (identifier.contains("@")) {
            return this.findByEnterpriseEmail(identifier);
        }
        return this.searchOneIdentifier(
                new Filters().in(UserIdentity::getLoginMobile, mobileSpellings), identifier);
    }

    /**
     * The person an email signs in: the one whose JOINED account carries it as the enterprise email,
     * in any tenant. {@code login_email} on the identity is no longer read — it was a copy of a work
     * email that went stale the moment the account's email changed, and off-boarding had to release
     * it by hand. Reading the account instead makes a closed account's email stop signing anyone in
     * by itself.
     *
     * <p>Consultants keep the old route: a consultant's address is their own, not a company's, and
     * until a grant mints an account there is no account to carry it.
     */
    private Optional<UserIdentity> findByEnterpriseEmail(String email) {
        Set<Long> people = accountService.findJoinedProfilesByEmail(email);
        if (people.size() > 1) {
            log.error("Enterprise email is held by several people's joined accounts. Refusing to guess.");
            throw AccountErrorCodes.refusal(AccountErrorCodes.CONTACT_SHARED, "This contact is shared by "
                    + "more than one account. Please contact your administrator.");
        }
        if (people.size() == 1) {
            return this.findByProfile(people.iterator().next());
        }
        return this.searchOneIdentifier(new Filters().eq(UserIdentity::getLoginEmail, email), email)
                .filter(identity -> consultantService.isConsultant(identity.getProfileId()));
    }

    private Optional<UserIdentity> searchOneIdentifier(Filters filters, String identifier) {
        try {
            return this.searchOne(filters);
        } catch (IllegalArgumentException e) {
            log.error("Login identifier is not unique — several people claim it. Refusing to guess.");
            throw AccountErrorCodes.refusal(AccountErrorCodes.CONTACT_SHARED, "This contact is shared by "
                    + "more than one account. Please contact your administrator.");
        }
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public boolean isIdentifierClaimable(String identifier, Long forProfileId) {
        List<String> spellings = LoginIdentifiers.loginSpellings(identifier);
        identifier = LoginIdentifiers.normalize(identifier);
        if (identifier == null) {
            return false;
        }
        boolean isEmail = identifier.contains("@");
        // A mobile under every spelling it may be stored in: a pre-fold row holding the number with
        // separators is a claimant too, and missing it would seed the same number twice.
        Filters filters = isEmail
                ? new Filters().eq(UserIdentity::getLoginEmail, identifier)
                : new Filters().in(UserIdentity::getLoginMobile, spellings);
        // searchList, not searchOne: the point is to COUNT claimants (0 = free, 1 = held, and if a
        // migration ever left more than one, searchOne would throw rather than count them).
        return this.searchList(filters).stream()
                .allMatch(other -> Objects.equals(other.getProfileId(), forProfileId));
    }


    // @SkipPermissionCheck / @CrossTenant like setPassword: a match can re-store the hash (below), and
    // the person checking their own current password before changing it holds no write grant on
    // UserIdentity — the upgrade would otherwise fail the change it was only riding along with.
    @SkipPermissionCheck
    @CrossTenant
    @Override
    public boolean matchesPassword(UserIdentity identity, String rawPassword) {
        String stored = identity == null ? null : identity.getPassword();
        String legacySalt = identity == null ? null : identity.getPasswordSalt();
        // No stored password = password login is not available for this person (they arrived by
        // invitation and have not set one). Returning true for an empty hash is the classic way that
        // rule gets broken: PasswordUtils.matches never does, and it takes as long to say so as a
        // real check — see the timing note on recordUnknownIdentifierFailure.
        if (!PasswordUtils.matches(rawPassword, stored, legacySalt)) {
            return false;
        }
        if (PasswordUtils.needsRehash(stored)) {
            this.rehash(identity, rawPassword);
        }
        return true;
    }

    /**
     * Store a password that has just verified against an older kind of hash — the one-round SHA-512
     * of earlier releases, or another iteration count — again in the current one. There is no other
     * moment to do it: the password is known only while its owner is typing it.
     *
     * <p>Written only while the row still holds the hash that was verified. A row read before its
     * password was changed elsewhere — on another device, or by an administrator's reset — would
     * otherwise carry a hash of the OLD password back over the new one, the same hazard
     * recordPasswordFailure avoids by writing the lock alone. Losing that race costs nothing: the row
     * is left as the other write left it.
     */
    private void rehash(UserIdentity identity, String rawPassword) {
        if (identity.getId() == null) {
            return;
        }
        String upgraded = PasswordUtils.hash(rawPassword);
        Map<String, Object> fields = new HashMap<>();
        fields.put(PASSWORD, upgraded);
        // The salt is inside the new hash; the column only served the old scheme.
        fields.put(PASSWORD_SALT, null);
        Filters stillTheVerifiedHash = new Filters().eq(UserIdentity::getId, identity.getId())
                .eq(UserIdentity::getPassword, identity.getPassword());
        if (modelService.updateByFilter(modelName, stillTheVerifiedHash, fields) > 0) {
            identity.setPassword(upgraded);
            identity.setPasswordSalt(null);
        }
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public void setPassword(UserAccount account, String rawPassword) {
        this.setPassword(requireIdentity(account).getId(), rawPassword);
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public void setPassword(Long identityId, String rawPassword) {
        UserIdentity identity = this.getById(identityId)
                .orElseThrow(() -> new BusinessException("Credentials not found."));
        // Enforced HERE rather than at each call site: invitation-accept, forced set-password,
        // self-service change and admin reset all land in this method, and a rule that every
        // caller must remember to apply is a rule that one of them eventually will not.
        PasswordPolicy.validate(rawPassword);
        // A new password ends the window AND the lock: the guesses were against the old password,
        // and leaving the lock standing would mean someone who reset theirs still cannot use it.
        Map<String, Object> fields = new HashMap<>();
        fields.put(PASSWORD, PasswordUtils.hash(rawPassword));
        // The salt is inside the hash; the column is cleared so it never pairs with a hash it is not
        // the salt of.
        fields.put(PASSWORD_SALT, null);
        fields.put(PASSWORD_LOCKED_UNTIL, null);
        this.updateFields(identity.getId(), fields);
        this.clearPasswordFailures(identityId);
    }

    @Override
    public boolean isPasswordLocked(UserIdentity identity) {
        return identity != null && identity.getPasswordLockedUntil() != null
                && identity.getPasswordLockedUntil().isAfter(LocalDateTime.now());
    }

    // @SkipPermissionCheck: this answers a DISPLAY question on the account roster, whose callers are
    // HR-ish roles granted UserAccount and nothing on UserIdentity. Left checked, the roster read
    // would fail closed on the credential model — the list every HR opens, refused over a badge.
    @SkipPermissionCheck
    @Override
    public Set<Long> findPasswordLockedProfiles(Collection<Long> profileIds) {
        if (profileIds == null || profileIds.isEmpty()) {
            return Set.of();
        }
        List<Long> people = profileIds.stream().filter(Objects::nonNull).distinct().toList();
        if (people.isEmpty()) {
            return Set.of();
        }
        // The "is it locked" clock stays in isPasswordLocked rather than becoming a second
        // `password_locked_until > now()` in SQL here: two spellings of one rule eventually disagree,
        // and the row count is bounded by the page the caller is rendering.
        return this.searchList(new Filters().in(UserIdentity::getProfileId, people)).stream()
                .filter(this::isPasswordLocked)
                .map(UserIdentity::getProfileId)
                .collect(Collectors.toSet());
    }

    // REQUIRES_NEW: the wrong password is reported by throwing, and a caller running in a transaction
    // (changing one's password does) rolls back on that very exception — taking the lock with it,
    // while the counter in the cache has already been reset. The lock then never holds, and every
    // tenth guess just starts a fresh window. Its own transaction commits it whatever the caller does.
    @SkipPermissionCheck
    @CrossTenant
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long recordPasswordFailure(UserIdentity identity) {
        if (identity == null || identity.getId() == null) {
            return 0;
        }
        Long failures = cacheService.increment(failureKey(identity.getId()), LOCK_MINUTES * 60L);
        if (failures == null) {
            return 0;
        }
        if (failures < FAILURES_BEFORE_LOCK) {
            return failures;
        }
        LocalDateTime lockedUntil = LocalDateTime.now().plusMinutes(LOCK_MINUTES);
        identity.setPasswordLockedUntil(lockedUntil);
        this.updateFields(identity.getId(), Map.of(PASSWORD_LOCKED_UNTIL, lockedUntil));
        // Counter cleared with the lock, so the next window starts fresh when it expires —
        // otherwise one wrong password after unlocking would immediately re-lock.
        this.clearPasswordFailures(identity.getId());
        log.warn("Password login locked for {} minutes after {} consecutive failures (identity {}).",
                LOCK_MINUTES, failures, identity.getId());
        return failures;
    }

    @Override
    public void updateFields(Long identityId, Map<String, Object> fields) {
        if (identityId == null) {
            throw new IllegalArgumentException("Credential row id is required.");
        }
        if (fields.isEmpty()) {
            return;
        }
        // HashMap rather than Map.of: the values carry nulls on purpose.
        Map<String, Object> row = new HashMap<>(fields);
        row.put(ID, identityId);
        modelService.updateOne(modelName, row);
    }

    @Override
    public void clearPasswordLock(Long identityId) {
        Map<String, Object> fields = new HashMap<>();
        fields.put(PASSWORD_LOCKED_UNTIL, null);
        this.updateFields(identityId, fields);
    }

    @Override
    public void updateLoginMobile(Long identityId, String loginMobile) {
        Map<String, Object> fields = new HashMap<>();
        fields.put(LOGIN_MOBILE, loginMobile);
        this.updateFields(identityId, fields);
    }

    /*
     * What remains distinguishable BY DESIGN between a known and an unknown identifier, once the
     * messages, counts, clocks and spelling agree:
     *
     *  - The real counter is per PERSON, this one per STRING. A person's email and mobile lock
     *    together (ten wrong passwords split across them lock both), while two unknown strings never
     *    share a count. An observer who can pair two identifiers and spend the window on them can
     *    learn that they belong to one real person. Accepted: keying the real counter per string
     *    would hand a person with two identifiers twenty guesses, and the pairing already requires
     *    knowing both of someone's contacts.
     *  - The storage medium differs. The real lock is persisted on the identity row so a cache flush
     *    cannot unlock it; the unknown lock lives only in the cache. A flush therefore frees unknown
     *    identifiers and not real ones — observable only by whoever can cause the flush.
     *  - Timing. Both branches run the same two identifier queries and the same password hash. The
     *    hash is slow on purpose (~70 ms), and a refusal that skipped it would say, by how long it
     *    took, whether the identifier is anyone's — so the unknown branch spends one through
     *    matchesPassword(null, …), and PasswordUtils.matches spends one for a person with no
     *    password yet or an old-scheme hash. What remains is that the real branch writes a row on
     *    the tenth failure and the unknown one increments a cache key: sub-millisecond, and below
     *    what the network jitter of an anonymous form exposes; not equalised further.
     */
    @Override
    public long recordUnknownIdentifierFailure(String identifier) {
        String value = LoginIdentifiers.normalize(identifier);
        if (value == null) {
            return 0;
        }
        // Same window as the real counter, so the two branches age identically.
        String digest = unknownDigest(value);
        Long failures = cacheService.increment(unknownFailureKey(digest), LOCK_MINUTES * 60L);
        if (failures == null) {
            return 0;
        }
        if (failures >= FAILURES_BEFORE_LOCK) {
            // Mirror recordPasswordFailure exactly: a lock that runs LOCK_MINUTES from THIS failure,
            // and a counter reset so the next window starts fresh. Letting the counter stand in for
            // the lock does not work — its TTL runs from the first failure, the real lock from the
            // tenth, and between those two expiries the branches give different answers.
            cacheService.save(unknownLockKey(digest), "1", LOCK_MINUTES * 60);
            cacheService.clear(unknownFailureKey(digest));
        }
        return failures;
    }

    @Override
    public boolean isUnknownIdentifierLocked(String identifier) {
        String value = LoginIdentifiers.normalize(identifier);
        return value != null && cacheService.hasKey(unknownLockKey(unknownDigest(value)));
    }

    private static String unknownFailureKey(String digest) {
        return "login:pwd-failures:unknown:" + digest;
    }

    private static String unknownLockKey(String digest) {
        return "login:pwd-lock:unknown:" + digest;
    }

    private static String unknownDigest(String identifier) {
        // Hashed rather than stored: the key would otherwise be a list of every identifier ever
        // guessed at the login form. The input is already in LoginIdentifiers' canonical form and
        // is hashed as-is: any further transformation here is one the known branch's lookup would
        // not make, and a difference between the two is the oracle this counter exists to close.
        return HashUtils.sha256Hex(identifier);
    }

    @Override
    public void clearPasswordFailures(Long identityId) {
        if (identityId != null) {
            cacheService.clear(failureKey(identityId));
        }
    }
}
