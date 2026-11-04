package io.softa.starter.user.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;

/**
 * The person's credentials: persistence AND the rules for reading and writing them.
 *
 * <p>One service, not two. "Where is the password" has to have exactly one answer — before the
 * credentials had a model of their own, that meant a separate seam ({@code UserCredentialService})
 * sitting in front of whichever model happened to hold them, because four services were hashing and
 * comparing passwords themselves and moving the storage meant finding every one of them. Now that
 * the credentials ARE a model, this service is that seam, and a second one in front of it would only
 * add a hop.
 *
 * <p>Deliberately exposes no unguarded lookup. Fetching an identity by profile id or by login
 * identifier is possible, but only through the methods below: a bare finder returning an empty
 * Optional invites the caller to read "no credentials" as "this person has no password", which is
 * how a password-less path gets opened. {@link #requireIdentity} refuses loudly instead.
 *
 * <p>Scoped to callers of this service; the generic {@code ModelController} surface reaches the model
 * directly.
 */
public interface UserIdentityService extends EntityService<UserIdentity, Long> {

    /**
     * The credentials of the person a membership belongs to, resolved from {@code account.profileId}.
     *
     * <p>Takes the account rather than its id on purpose. Looking it up here would mean depending on
     * {@link UserAccountService}, which depends on this service for every password it writes — a bean
     * cycle Spring refuses outright. Every caller already holds the account anyway.
     */
    UserIdentity requireIdentity(UserAccount account);

    /**
     * The person's credentials, or empty when they have none yet.
     *
     * <p>The lenient twin of {@link #requireIdentity}: for callers that must keep working when the
     * row is absent — off-boarding has to close a membership whether or not credentials were ever
     * minted, and refusing there would leave the membership open on a data fault.
     */
    Optional<UserIdentity> findByProfile(Long profileId);


    /**
     * Whether this identifier is free to become someone's LOGIN identifier.
     *
     * <p>A work contact and a login identifier are different roles for the same string, and only
     * one of them has to be unique. Shared work numbers are ordinary — a shop's phone, a shared
     * floor handset, a manager's number entered for a worker who has none — and nothing is wrong
     * with that as a CONTACT. As a login identifier it is unusable: a code sent there cannot say
     * which of the holders is signing in, so resolution can only refuse.
     *
     * <p>Every path that seeds an identifier from a work contact must ask this first. Copying the
     * contact across regardless does not create a login route, it destroys one: both holders then
     * resolve to "shared by more than one account" instead of the one who had it to themselves.
     */
    boolean isIdentifierClaimable(String identifier, Long forProfileId);

    /**
     * Finds a person's credentials by an email OR a dial-code mobile.
     *
     * <p>⚠️ NO CALLER THIS RELEASE — deliberately. Login still resolves the account by its email;
     * this (and the loginEmail/loginMobile columns it queries, seeded but unread) is the seam the
     * "resolve people by identifier" release will switch to. It ships now so that release needs no
     * data backfill — the expensive half of that change is the data, and it is being populated from
     * day one. Kept rather than deferred on purpose; do not delete as "dead code".
     */
    Optional<UserIdentity> findByLoginIdentifier(String identifier);

    /** False when the person has no stored password — that is not the same as "any password fits". */
    boolean matchesPassword(UserIdentity identity, String rawPassword);

    /**
     * Whether PASSWORD login is currently locked for this person.
     *
     * <p>The lock is a property of the PERSON, so it covers every company they belong to at once —
     * that is the "linked lockout across tenants" requirement, and it falls out of the credential
     * being global rather than needing coordination. Switching company buys no extra attempts.
     *
     * <p>Only the PASSWORD path is locked. Code login stays available throughout: what was under
     * attack is the password, and locking someone out of their own account entirely would turn a
     * guessing attempt against them into a denial of service.
     */
    boolean isPasswordLocked(UserIdentity identity);

    /**
     * Which of these people currently have their PASSWORD login locked — one query for the whole set.
     *
     * <p>Exists for the account list, which reports the lock as a badge per row. Asking
     * {@link #findByProfile} row by row would put a query per listed account on the roster read;
     * the page's people are collected and resolved together instead.
     *
     * <p>Returns profile ids rather than identities on purpose: the caller wants the answer, and
     * handing out credential rows for a display decision spreads them further than the question needs.
     */
    Set<Long> findPasswordLockedProfiles(Collection<Long> profileIds);

    /** Consecutive wrong passwords that lock the password route. */
    int FAILURES_BEFORE_LOCK = 10;

    /**
     * Count one wrong password, locking the person out once too many land in a row.
     *
     * <p>Counted in the cache rather than on the row: the counter is worthless after the window
     * and writing the row on every wrong guess would let anyone generate write load at will. Only
     * the lock itself is persisted, because it must survive a cache flush — otherwise clearing the
     * cache would be an unlock.
     *
     * @return the failure count in the current window, this one included; on the failure that
     *         locks, the count that locked (the login path words its refusal from it)
     */
    long recordPasswordFailure(UserIdentity identity);

    /**
     * Count one wrong password against an identifier that resolves to NOBODY.
     *
     * <p>Without this, an unknown identifier is an oracle: the real counter starts naming the
     * remaining attempts from the seventh failure, so whoever sees a countdown after seven tries
     * has learned the identifier exists, and whoever never sees one has learned it does not. The
     * submitted identifier is therefore counted too — keyed by a digest of its canonical form (LoginIdentifiers), so
     * the cache never holds the raw guesses — in the same window and to the same threshold, and the
     * login path words its refusal from this count exactly as it does from the real one.
     *
     * <p>On the failure that would lock a real person, a lock keyed the same way is set for the same
     * duration and the counter is cleared — the real branch's exact clock. A counter that merely
     * kept climbing until its TTL ran out would expire {@code LOCK_MINUTES} after the FIRST failure,
     * while the real lock starts at the TENTH: spread ten tries over twenty minutes and, a quarter
     * of an hour later, the real identifier still says "locked" and the made-up one says "incorrect".
     *
     * @return the failure count in the current window, this one included
     */
    long recordUnknownIdentifierFailure(String identifier);

    /**
     * Whether an identifier that resolves to nobody is currently "locked" — the twin of
     * {@link #isPasswordLocked} for the unknown branch, read before counting for the same reason
     * the real lock is: a locked person is refused without their guess being counted, so a locked
     * unknown identifier must be too, or the two diverge on the eleventh try.
     */
    boolean isUnknownIdentifierLocked(String identifier);

    /** Forget the failure count — a successful login, or a new password, ends the window. */
    void clearPasswordFailures(Long identityId);

    /**
     * Writes ONLY the named columns of one credential row; every other column is left as the
     * database has it.
     *
     * <p>The way to change a credential row. Reading the row, changing a field and writing the
     * object back ({@code updateOne(identity, false)}) also writes every column the caller did not
     * mean to touch, with the values it read: a password changed elsewhere between that read and
     * that write is silently put back to the old hash. Writes on this row come from login, from
     * self-service, from HR and from off-boarding at once, so that interleaving is ordinary.
     *
     * <p>A {@code null} value is written as {@code null} — clearing a lock or a login mobile is
     * one of the writes this exists for.
     *
     * @param fields field name to value; field names as {@code LambdaUtils.getAttributeName} gives them
     */
    void updateFields(Long identityId, Map<String, Object> fields);

    /** Clears the password lock, and nothing else, on one credential row. */
    void clearPasswordLock(Long identityId);

    /** Sets (or, with {@code null}, clears) the login mobile, and nothing else, on one credential row. */
    void updateLoginMobile(Long identityId, String loginMobile);

    /** Hashes and stores a new password on the person's credentials, with a fresh salt. */
    void setPassword(Long identityId, String rawPassword);

    /**
     * Same, for the person behind a membership — the common case.
     *
     * <p>Exists because "resolve the identity, take its id, set the password" was written out at
     * every call site, and nesting the three reads badly enough to hide the interesting part: which
     * account the password lands on.
     */
    void setPassword(UserAccount account, String rawPassword);
}
