package io.softa.starter.user.service.impl;

import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import io.softa.framework.base.utils.RandomUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.UserNotFoundException;
import io.softa.framework.base.security.PasswordUtils;
import io.softa.framework.base.utils.UUIDUtils;
import java.util.Map;
import io.softa.framework.base.message.MailRequestMessage;
import io.softa.framework.base.message.SmsRequestMessage;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.dto.AuthenticationResult;
import io.softa.starter.user.dto.JoinContacts;
import io.softa.starter.user.dto.JoinPasswordResult;
import io.softa.starter.user.dto.JoinVerification;
import io.softa.starter.user.dto.InvitationInfo;
import io.softa.starter.user.dto.MembershipOption;
import io.softa.starter.user.exception.MultipleMembershipsException;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.LoginService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;
import io.softa.framework.web.session.ReentryTokenIssuer;
import io.softa.framework.web.session.SessionErrorCodes;
import io.softa.framework.web.session.SessionStore;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.starter.user.constant.AccountErrorCodes;
import io.softa.starter.user.enums.LoginChannel;
import io.softa.starter.user.enums.LoginEvent;
import io.softa.starter.user.enums.LoginMethod;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.enums.SecurityLogSource;
import io.softa.starter.user.service.UserSecurityLogService;
import io.softa.starter.user.service.UserSessionService;
import io.softa.starter.user.spi.PushDeviceRegistry;
import io.softa.starter.user.util.ClientInfo;
import io.softa.starter.user.util.LoginIdentifiers;
import io.softa.starter.user.service.ConsultantService;

/**
 * UserAccount Model Service Implementation
 */
@Slf4j
@Service
public class LoginServiceImpl implements LoginService, ReentryTokenIssuer {

    /** Mirrors UserIdentityServiceImpl's window, for the message the locked-out person sees. */
    private static final int LOCK_MINUTES = 30;

    /**
     * The memberships the company step counts: Active, Frozen and Locked.
     *
     * <p>An INVITED or PENDING row is a membership HR has created, not one the person holds yet:
     * counting it sends someone with one real company and one open invitation to the picker they
     * should skip, and the picker then lists a company they have not accepted into.
     *
     * <p>LOCKED is legacy data — the lock moved to the credential and nothing writes this status any
     * more — but rows carrying it must still surface, or their owner silently loses the company.
     */
    private static final Set<AccountStatus> COUNTED_STATUSES =
            Set.of(AccountStatus.ACTIVE, AccountStatus.FROZEN, AccountStatus.LOCKED);

    /** Someone whose only memberships are unaccepted invitations — see {@link #noCompanyRefusal}. */
    private static final String INVITATION_PENDING_MESSAGE =
            "Your invitation has not been accepted yet. Open the link we sent you.";

    private static final String NO_COMPANY_MESSAGE =
            "Your account is not linked to any company. Please contact your administrator.";

    /**
     * A consultant whose grants have all lapsed. Distinct from NO_COMPANY_MESSAGE because
     * the remedy is different and so is the person to ask: nothing is wrong with their account, an
     * authorization simply ended, and it is the PLATFORM that extends it — telling them to contact
     * "your administrator" would send them to a tenant that never granted the access.
     */
    private static final String CONSULTANT_NO_ACCESS_MESSAGE =
            "No accessible tenant. Please contact the platform administrator.";

    /**
     * The first wrong password that is answered with a remaining-attempts count. Early
     * guesses get the bare refusal, because a countdown from the first attempt tells whoever is
     * guessing exactly how many tries they have left; the warning appears only once a lock is
     * near, when its value is to the legitimate owner who mistyped.
     */
    private static final int WARN_FROM_FAILURE = 7;
    /** Verification-code template, one code for both channels (MailTemplate / SmsTemplate share it). */
    private static final String TEMPLATE_CODE = "user.verification-code";
    /** Code lifetime shown to the recipient; kept in step with VerificationCodeGuard.CODE_TTL_SECONDS. */
    private static final int CODE_EXPIRY_MINUTES = VerificationCodeGuard.CODE_TTL_SECONDS / 60;
    /** Entropy of the pre-auth token that bridges authentication and the company step. */
    private static final int PREAUTH_TOKEN_BYTES = 32;
    /** How long the person has to pick a company before re-authenticating. */
    private static final int PREAUTH_TTL_SECONDS = 600;
    /**
     * Lookups of identifiers that turn out to be nobody's, per client address and hour. The send
     * limits are per identifier, so a miss costs nothing against them; without this a script could
     * walk a number range and learn which numbers are employees'.
     */
    static final int UNKNOWN_LOOKUPS_PER_HOUR = 30;
    private static final int UNKNOWN_LOOKUP_WINDOW_SECONDS = 3600;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private UserAccountService accountService;

    @Autowired
    private UserProfileService profileService;

    @Autowired
    private UserIdentityService identityService;

    @Autowired(required = false)
    private TenantInfoService tenantInfoService;

    @Autowired(required = false)
    private AddressAttemptLimiter addressLimiter;

    @Autowired
    private UserInvitationService invitationService;

    /** Send / attempt limits for one-time codes — see the class for why each exists. */
    @Autowired
    private VerificationCodeGuard codeGuard;

    /** Consultant access is a grant with dates, not a status — only this can say if it is live. */
    @Autowired
    private ConsultantService consultantService;

    /** Carries "the code was passed" into the anonymous set-password / confirm steps. */
    @Autowired
    private JoinProofGuard proofGuard;

    @Autowired
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private UserSessionService sessionService;

    @Autowired
    private LoginHistoryRecorder history;

    @Autowired
    private UserSecurityLogService securityLog;

    @Autowired
    private JoinMobileFormat mobileFormat;

    /** Optional: present only where APP push is deployed. */
    @Autowired(required = false)
    private PushDeviceRegistry pushDeviceRegistry;

    /**
     * Tenant lifecycle gate at login: only ACTIVE tenants may log in. Enforced at the single
     * session-issuance choke point ({@link #generateSessionId}), so every login flow — password,
     * email/mobile code, and OAuth — is covered, and the user gets a clear reason before a
     * session is issued (rather than a session the per-request gate would immediately reject).
     * No-op when multi-tenancy is disabled.
     *
     * @param userId the user being logged in (its tenantId is resolved here)
     */
    private void validateTenantActive(Long userId) {
        if (!SystemConfig.env.isEnableMultiTenancy()) {
            return;
        }
        UserInfo userInfo = profileService.getUserInfo(userId);
        Long tenantId = userInfo == null ? null : userInfo.getTenantId();
        if (tenantInfoService == null) {
            throw new BusinessException("Login denied: tenant is not active.");
        }
        // Not-yet-built is checked FIRST, for its message. Both states now live on one field, so a tenant mid
        // setup is also not ACTIVE — asking "is it active" first would answer every in-flight tenant with the
        // generic "not active" and lose the one thing the user can act on ("it is still being set up, wait").
        //
        // The check earns its place beyond the wording: letting someone in mid-seed shows a workspace whose
        // roles and org masters are still arriving over MQ, and lets them create records pointing at masters
        // that do not exist yet. It is also what makes discarding a failed setup safe — if nobody can get in
        // before it goes ACTIVE, every row in the tenant came from a seeder.
        if (!tenantInfoService.isTenantProvisioned(tenantId)) {
            throw new BusinessException("Login denied: this workspace is still being set up.");
        }
        if (!tenantInfoService.isTenantActive(tenantId)) {
            throw new BusinessException("Login denied: tenant is not active.");
        }
    }

    /**
     * Account lifecycle gate at login: only an ACTIVE account may obtain a session.
     * Sits at the same session-issuance choke point as {@link #validateTenantActive},
     * so every login flow (password / email code / mobile code / OAuth / Apple) is
     * covered. It runs AFTER credentials are verified, so returning the specific
     * reason is safe — the caller has already proven identity, so this is not an
     * account-enumeration channel.
     *
     * <p>Closes the gap where an employee off-boarded to INACTIVE (mirrored onto
     * UserAccount.status = Frozen) could still authenticate, because login only
     * checked the password and never the account state.
     */
    private UserAccount validateAccountActive(Long userId) {
        UserAccount account = accountService.getById(userId)
                .orElseThrow(() -> new BusinessException("Login denied: account not found."));
        // LOCKED is legacy data (the lock lives on the person now) and, like a derived lock, only
        // ever refused PASSWORD login — which has been decided by the time a session is issued.
        if (account.getStatus() == AccountStatus.ACTIVE || account.getStatus() == AccountStatus.LOCKED) {
            return account;
        }
        throw orgUnavailable(accountDeniedMessage(account.getStatus()));
    }

    /**
     * Picking a web-only account on the APP — a consultancy, or the platform's own — is refused as
     * such, rather than as the "not available" a filtered-out row would get: the person should learn
     * to use the web, not that it vanished.
     */
    private void refuseConsultancyOnApp(Long accountId) {
        if (accountId == null || !ClientInfo.current().isApp()) {
            return;
        }
        BusinessException refusal = accountService.getById(accountId).map(LoginServiceImpl::webOnlyRefusal).orElse(null);
        if (refusal != null) {
            throw refusal;
        }
    }

    /**
     * Why this account cannot be entered from the APP, or null when it can: the APP is for
     * employees, and both a consultancy and the platform's own accounts are worked on the web.
     */
    static BusinessException webOnlyRefusal(UserAccount account) {
        if (BaseConstant.PLATFORM_TENANT_ID.equals(account.getTenantId())) {
            return platformOnApp();
        }
        if (Boolean.TRUE.equals(account.getConsultant())) {
            return consultantOnApp();
        }
        return null;
    }

    /** Platform accounts sign in on the web only. */
    static BusinessException platformOnApp() {
        return AccountErrorCodes.refusal(AccountErrorCodes.PLATFORM_APP_UNSUPPORTED,
                "Platform accounts can only sign in on the web.");
    }

    /** Consultant accounts sign in on the web only. */
    static BusinessException consultantOnApp() {
        return AccountErrorCodes.refusal(AccountErrorCodes.CONSULTANT_APP_UNSUPPORTED,
                "Consultant accounts can only sign in on the web.");
    }

    /** A company that cannot be entered right now, with the code the APP re-runs the entry rule on. */
    private static BusinessException orgUnavailable(String message) {
        BusinessException e = new BusinessException(message);
        e.withErrorCode(SessionErrorCodes.ORG_UNAVAILABLE);
        return e;
    }

    /** Human-readable reason for refusing login to a non-ACTIVE account. */
    /** Why a listed company cannot be entered: the company itself, or the account in it. */
    private static String deniedMessage(MembershipOption chosen) {
        return chosen.unavailableReason() != null ? "Login denied: tenant is not active."
                : accountDeniedMessage(chosen.status());
    }

    private static String accountDeniedMessage(AccountStatus status) {
        // No default branch on purpose: the switch is exhaustive over the six-value axis, so
        // adding a status makes this fail to COMPILE rather than silently fall through to
        // "not active" — which is how the removed values earned their vague message.
        String reason = switch (status == null ? AccountStatus.FROZEN : status) {
            case FROZEN -> "your account has been deactivated";
            case DEACTIVATED -> "your membership of this company has ended";
            case LOCKED -> "your account is locked";
            case PENDING -> "your account has not been invited yet — ask your administrator to send the invitation";
            case INVITED -> "your account invitation has not been accepted yet";
            case ACTIVE -> "your account is not active";
        };
        return "Login denied: " + reason + ".";
    }

    public static String buildLoginCodeKey(String identifier) {
        // Partition by login scenario
        return RedisConstant.VERIFICATION_CODE + "login:" + identifier;
    }

    /**
     * Issue a code for this identifier, subject to the send limits.
     *
     * <p>The rate check runs BEFORE generating: an over-limit request must be refused without
     * overwriting a code the user may still be typing.
     */
    private String generateNumericCode(String identifier) {
        codeGuard.beforeSend(identifier);
        String code = RandomStringUtils.secure()
                .nextNumeric(VerificationCodeGuard.CODE_LENGTH);
        codeGuard.store(identifier, code);
        return code;
    }

    public void verifyCode(String identifier, String inputCode) {
        codeGuard.verify(identifier, inputCode);
    }

    @Override
    public void sendEmailCode(String email) {
        this.requireLinked(email);
        this.deliverEmailCode(email);
    }

    @Override
    public void sendMobileCode(String mobile) {
        this.requireLinked(mobile);
        this.deliverMobileCode(mobile);
    }

    /**
     * The person behind a login identifier, refused with {@code ACCOUNT_NOT_FOUND} when there is
     * none — no identity, or an identity no account was ever created for.
     *
     * <p>Said out loud: the screen tells a mistyped number "contact HR" instead of pretending a code
     * is on its way. That makes sending and signing in an answer to "is this number an employee's?".
     * The send limits in {@link VerificationCodeGuard} count per identifier, so they never see a
     * miss; what bounds walking a range of numbers is the per-address limit on misses here. The
     * lookup gets the TYPED form, as every identifier lookup here does (see {@link LoginIdentifiers}).
     */
    private UserIdentity requireLinked(String identifier) {
        Optional<UserIdentity> found = identityService.findByLoginIdentifier(LoginIdentifiers.typedForm(identifier))
                .filter(identity -> !accountService.listAccountIdsOf(identity.getProfileId()).isEmpty());
        if (found.isPresent()) {
            // A spent miss budget refuses misses only: an address shared by an office must not lock
            // its real employees out because someone there mistyped, or probed, too often.
            return found.get();
        }
        String misses = unknownLookupKey();
        if (misses != null && NumberUtils.toLong(cacheService.get(misses)) >= UNKNOWN_LOOKUPS_PER_HOUR) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.TOO_MANY_REQUESTS,
                    "Too many attempts. Please try again later.");
        }
        if (misses != null) {
            cacheService.increment(misses, UNKNOWN_LOOKUP_WINDOW_SECONDS);
        }
        throw AccountErrorCodes.refusal(AccountErrorCodes.ACCOUNT_NOT_FOUND,
                "This account does not exist. Please contact your HR.");
    }

    /** The miss counter of the calling address, or null when the address is unknown (no request). */
    private static String unknownLookupKey() {
        String ip = ClientInfo.current().ip();
        return ip == null ? null : "login:unknown-identifier:" + ip;
    }

    /** The same lookup on a sign-in path, where a refusal is also a failed sign-in in the history. */
    private UserIdentity requireLinkedToSignIn(String identifier, LoginChannel channel, LoginMethod method) {
        try {
            return this.requireLinked(identifier);
        } catch (BusinessException e) {
            history.record(LoginEvent.LOGIN_FAILED, null, null, null, channel, method,
                    StringUtils.defaultString(e.getErrorCode(), e.getMessage()));
            throw e;
        }
    }

    /**
     * Send a code to an address that has ALREADY been established as the right one to send to —
     * by a login identifier that resolves, or by an invitation that named it.
     *
     * <p>Separate from {@link #sendEmailCode} because /join legitimately sends to someone who has
     * no identity yet: the invitation is the authority there, and the address came from the
     * invitation rather than from the caller, so the linked check would skip every first-time
     * joiner and the code would never go out.
     */
    private void deliverEmailCode(String email) {
        // Normalised once, here, and the normalised value is what the code is keyed by AND sent to:
        // the verify step normalises its identifier the same way, so the two meet on one key
        // however the person spelt the address on either screen.
        email = LoginIdentifiers.normalize(email);
        String code = this.generateNumericCode(email);
        // PLATFORM tier: a login / join code is requested BEFORE any session, so there is no tenant
        // context to render a tenant-specific template from — the platform row is the code's copy.
        // Absent message-starter this is a graceful no-op, same as every other request message.
        eventPublisher.publishEvent(new MailRequestMessage(
                List.of(email), TEMPLATE_CODE,
                Map.of("code", code, "expiryMinutes", CODE_EXPIRY_MINUTES), null, MessageScope.PLATFORM));
    }

    /** The mobile twin of {@link #deliverEmailCode}; see there for why it is separate. */
    private void deliverMobileCode(String mobile) {
        mobile = LoginIdentifiers.normalize(mobile);
        String code = this.generateNumericCode(mobile);
        eventPublisher.publishEvent(new SmsRequestMessage(
                List.of(mobile), TEMPLATE_CODE,
                Map.of("code", code, "expiryMinutes", CODE_EXPIRY_MINUTES)));
    }

    @Override
    public AuthenticationResult authenticateByCode(String identifier, String code) {
        // The code is keyed by the canonical form; the lookups get the typed form, so a row seeded
        // with the separators the person still types is found too (LoginIdentifiers.loginSpellings).
        LoginChannel channel = LoginChannel.of(identifier);
        LoginMethod method = channel == LoginChannel.EMAIL ? LoginMethod.EMAIL_CODE : LoginMethod.SMS_CODE;
        UserIdentity identity = this.requireLinkedToSignIn(identifier, channel, method);
        try {
            verifyCode(LoginIdentifiers.normalize(identifier), code);
        } catch (BusinessException e) {
            history.record(LoginEvent.LOGIN_FAILED, identity.getProfileId(), null, null, channel, method,
                    StringUtils.defaultString(e.getErrorCode(), e.getMessage()));
            throw e;
        }
        // A lock refuses the PASSWORD only; a code still signs the person in, and into every
        // company, with nothing reduced.
        return this.signedIn(identity, channel, method);
    }

    @Override
    public AuthenticationResult authenticateByPassword(String identifier, String password) {
        LoginChannel channel = LoginChannel.of(identifier);
        if (addressLimiter != null) {
            addressLimiter.count();
        }
        UserIdentity identity = this.requireLinkedToSignIn(identifier, channel, LoginMethod.PASSWORD);
        Long profileId = identity.getProfileId();

        // Lock checked BEFORE the password, so "locked" versus "incorrect" cannot confirm a guess.
        // Only the password path is locked — code login stays open, because what is under attack
        // is the password and locking the person out entirely would complete the attack for it.
        if (identityService.isPasswordLocked(identity)) {
            history.record(LoginEvent.LOGIN_FAILED, profileId, null, null, channel, LoginMethod.PASSWORD,
                    AccountErrorCodes.PASSWORD_LOCKED);
            throw AccountErrorCodes.refusal(AccountErrorCodes.PASSWORD_LOCKED, lockedMessage());
        }
        if (identity.getPasswordLockedUntil() != null) {
            // A lock that ran out on its own: nothing marked the moment, so it is recorded here, at
            // the first password attempt after it — and cleared, so it is recorded once.
            history.record(LoginEvent.UNLOCKED, profileId, null, null, channel, null, null);
            identity.setPasswordLockedUntil(null);
            identityService.updateOne(identity, false);
        }
        if (!identityService.matchesPassword(identity, password)) {
            // Counted per PERSON, so switching company buys no extra tries.
            long failures = identityService.recordPasswordFailure(identity);
            history.record(LoginEvent.LOGIN_FAILED, profileId, null, null, channel, LoginMethod.PASSWORD,
                    AccountErrorCodes.PASSWORD_INCORRECT);
            if (failures >= UserIdentityService.FAILURES_BEFORE_LOCK) {
                history.record(LoginEvent.LOCKED, profileId, null, null, channel, LoginMethod.PASSWORD, null);
                // This guess is the one that locked: say so, rather than a plain "incorrect"
                // followed by an unexplained "locked" on the next try.
                throw AccountErrorCodes.refusal(AccountErrorCodes.PASSWORD_LOCKED, lockedMessage());
            }
            throw wrongPassword(failures);
        }
        identityService.clearPasswordFailures(identity.getId());
        return this.signedIn(identity, channel, LoginMethod.PASSWORD);
    }

    /** Record the successful sign-in, then decide where it goes. */
    private AuthenticationResult signedIn(UserIdentity identity, LoginChannel channel, LoginMethod method) {
        AuthenticationResult result;
        try {
            result = this.afterAuthentication(identity);
        } catch (BusinessException e) {
            // Proven, and still nowhere to go (a consultant whose grants all lapsed).
            history.record(LoginEvent.LOGIN_FAILED, identity.getProfileId(), null, null, channel, method,
                    StringUtils.defaultString(e.getErrorCode(), e.getMessage()));
            throw e;
        }
        UserInfo entered = result.userInfo();
        history.record(LoginEvent.LOGIN_SUCCESS, identity.getProfileId(),
                entered == null ? null : entered.getUserId(), entered == null ? null : entered.getTenantId(),
                channel, method, null);
        return result;
    }

    static String lockedMessage() {
        return "Too many failed attempts. Password login is locked for " + LOCK_MINUTES
                + " minutes. You can still log in with a verification code.";
    }

    /**
     * The refusal for the {@code failures}-th consecutive wrong password. From the seventh it names
     * the attempts left — early guesses get the bare refusal, because a countdown from the
     * first attempt tells whoever is guessing exactly how many tries they have left.
     */
    static BusinessException wrongPassword(long failures) {
        if (failures < WARN_FROM_FAILURE) {
            return AccountErrorCodes.refusal(AccountErrorCodes.PASSWORD_INCORRECT, "Incorrect account or password.");
        }
        long left = UserIdentityService.FAILURES_BEFORE_LOCK - failures;
        BusinessException e = AccountErrorCodes.refusal(AccountErrorCodes.PASSWORD_INCORRECT,
                "Incorrect account or password. " + left + (left == 1 ? " attempt" : " attempts")
                        + " left before password login locks for " + LOCK_MINUTES + " minutes.");
        e.withErrorDetail("remainingAttempts", left);
        return e;
    }

    /**
     * The shared tail of every authentication path: decide what the client must do next.
     *
     * <p>One place rather than per-path, because the three questions (needs a password? one
     * company or a choice? which membership?) have the same answers however the person proved
     * who they are — and a path that skipped one of them would be a hole rather than a variation.
     */
    private AuthenticationResult afterAuthentication(UserIdentity identity) {
        Long profileId = identity.getProfileId();
        boolean mustSetPassword = mustSetPasswordFor(identity);
        // Read once for the whole decision: the options, and each refusal below, look at the same rows.
        List<UserAccount> memberships = accountService.listMembershipsOf(profileId);
        List<MembershipOption> options = this.resolveMemberships(profileId, memberships);
        if (options.isEmpty() && ClientInfo.current().isApp()) {
            // The APP lists no consultancy and no platform account (resolveMemberships), so someone
            // holding only those has nothing there at all — said as what it is, not as "no company".
            if (memberships.stream()
                    .anyMatch(account -> BaseConstant.PLATFORM_TENANT_ID.equals(account.getTenantId()))) {
                throw platformOnApp();
            }
            if (consultantService.isConsultant(profileId)) {
                throw consultantOnApp();
            }
        }
        if (options.isEmpty() && holdsOnlyConsultantMemberships(profileId, memberships)) {
            // A consultant whose grants have all lapsed is not an employee with no company: the
            // platform, not a tenant, is who can help, and the two reasons below cannot say that.
            throw AccountErrorCodes.refusal(AccountErrorCodes.CONSULTANT_NO_ACCESS, CONSULTANT_NO_ACCESS_MESSAGE);
        }
        Long autoEnter = autoEnterAccount(options);
        // A password owed comes first: no session until it is set, even when the entry rule already
        // knows where the person will go — setFirstPassword enters it after.
        if (autoEnter != null && !mustSetPassword) {
            return AuthenticationResult.resolved(profileId, profileService.getUserInfo(autoEnter), false);
        }
        AuthenticationResult.NoEnterableReason reason = noEnterableReason(memberships, options);
        // A choice is pending (or a password is), so authentication succeeded but no session is
        // issued yet. Mint a single-use token proving THIS person just authenticated; selectTenant
        // and setFirstPassword read the person from it, never from a client-supplied id — otherwise
        // the next step would be an unauthenticated "issue me a session for profileId X". Nobody
        // needs one with nowhere to go and nothing owed.
        // Owing a password earns a token even with nowhere to enter: setting it is the one step the
        // person can still take.
        String token = reason == null || mustSetPassword ? issuePreAuthToken(profileId) : null;
        return AuthenticationResult.choicePending(profileId, options, mustSetPassword, token, autoEnter, reason);
    }

    private static String preAuthKey(String token) {
        return "login:preauth:" + token;
    }

    /**
     * A single-use token standing for "this person just authenticated". It carries when it was
     * issued, so a credential change after that — Reset User replacing the login mobile, a password
     * reset — voids it: whoever authenticated with the old number or password must not still be
     * able to pick a company and get a session.
     */
    private String issuePreAuthToken(Long profileId) {
        String token = RandomUtils.randomString(PREAUTH_TOKEN_BYTES);
        cacheService.save(preAuthKey(token), profileId + ":" + System.currentTimeMillis(), PREAUTH_TTL_SECONDS);
        return token;
    }

    @Override
    public String issueReentryToken(Long profileId) {
        return profileId == null ? null : this.issuePreAuthToken(profileId);
    }

    /** The person a live pre-auth token stands for, or a refusal if it expired / never existed. */
    private Long resolvePreAuthToken(String authToken) {
        String value = StringUtils.isBlank(authToken) ? null : cacheService.get(preAuthKey(authToken));
        Long profileId = this.personOf(value);
        if (profileId == null && value != null) {
            cacheService.clear(preAuthKey(authToken));
        }
        if (profileId == null) {
            throw preAuthExpired();
        }
        return profileId;
    }

    /**
     * Spends a pre-auth token on a step that mints something with it. The token is taken in one
     * atomic read-and-delete, so two requests presenting it at once cannot both get through; a step
     * that fails puts it back for the rest of its lifetime, so the person can try again without
     * signing in over.
     */
    private <T> T spendingPreAuthToken(String authToken, java.util.function.Function<Long, T> step) {
        String value = StringUtils.isBlank(authToken) ? null : cacheService.getAndClear(preAuthKey(authToken));
        Long profileId = this.personOf(value);
        if (profileId == null) {
            throw preAuthExpired();
        }
        try {
            return step.apply(profileId);
        } catch (RuntimeException e) {
            long left = PREAUTH_TTL_SECONDS - (System.currentTimeMillis() - issuedAtOf(value)) / 1000;
            if (left > 0) {
                cacheService.save(preAuthKey(authToken), value, (int) left);
            }
            throw e;
        }
    }

    /** The person a token value names, or null when there is none or a credential changed since. */
    private Long personOf(String value) {
        if (value == null) {
            return null;
        }
        Long profileId = Long.valueOf(value.split(":", 2)[0]);
        return sessionService.credentialsChangedSince(profileId, issuedAtOf(value)) ? null : profileId;
    }

    private static long issuedAtOf(String value) {
        String[] parts = value.split(":", 2);
        return parts.length > 1 ? Long.parseLong(parts[1]) : 0L;
    }

    private static BusinessException preAuthExpired() {
        return AccountErrorCodes.refusal(AccountErrorCodes.AUTH_TOKEN_EXPIRED,
                "Your sign-in step expired. Please log in again.");
    }

    @Override
    public AuthenticationResult confirmJoin(String rawToken, Long profileId, String proof) {
        // Read BEFORE the membership activates: accepting spends the invitation, and the row can no
        // longer be resolved from the token after. So is the contact the proof was minted for.
        Optional<UserAccount> joining = invitationService.resolveJoinAccount(rawToken);
        AuthenticationResult.JoinPrefill prefill = joining.isPresent() ? this.joinPrefill(rawToken, proof) : null;

        invitationService.confirmJoin(rawToken, profileId, proof);

        // A re-entrant confirm — the link already spent, a double tap — has no proof behind it any
        // more, and the profileId is only what the caller sent: no session and no sign-in token for
        // it. The person signs in like anyone else.
        if (joining.isEmpty()) {
            return AuthenticationResult.requireSignIn();
        }
        UserIdentity identity = identityService.findByProfile(profileId)
                .orElseThrow(() -> new BusinessException("Person record not found."));
        // A password still owed comes first, as at sign-in: no session until it is set; the
        // sign-in token lets setFirstPassword set it and enter.
        if (mustSetPasswordFor(identity)) {
            AuthenticationResult owed = AuthenticationResult.choicePending(profileId, this.resolveMemberships(profileId),
                    true, issuePreAuthToken(profileId), joining.get().getId(), null);
            return prefill == null ? owed : owed.withPrefill(prefill);
        }
        // Who this is was settled by the codes verified before (the mobile one included, when the
        // email alone could not settle it), so the person goes straight into the company just
        // joined, whatever else they belong to.
        AuthenticationResult result = AuthenticationResult.resolved(profileId,
                profileService.getUserInfo(joining.get().getId()), false);
        // The full contact they just verified — only ever to them — so the "get the APP" page can
        // prefill the login screen; the page itself only ever saw it masked.
        return prefill == null ? result : result.withPrefill(prefill);
    }

    @Override
    public void sendJoinCode(String rawToken, String channel) {
        // The address never crosses the wire in either direction: the caller sends a token, the
        // invitation service resolves it, and the code goes out to what IT stored.
        String address = invitationService.resolveJoinChannel(rawToken, channel);
        // deliver*, not send*: the invitee has no identity yet — that is what joining is — so the
        // linked check the public entry points carry would silently skip every first-time joiner.
        if ("mobile".equalsIgnoreCase(channel)) {
            this.deliverMobileCode(address);
        } else {
            this.deliverEmailCode(address);
        }
    }

    /**
     * Verify the join code, then decide who is joining.
     *
     * <ul>
     *   <li><b>Mobile</b>: the number is the person's login mobile. Its holder is this person; nobody
     *       holding it (or only a dormant holder, released first) → a new person with it.</li>
     *   <li><b>Email</b>: proves this is the employee the record names, not that the person found by
     *       the record's personal mobile is them. Nobody holding the mobile (or a dormant holder) → a
     *       new person with it; somebody holding it → {@code VERIFY_MOBILE}: the same flow again on
     *       the mobile channel, and no proof until then. The exceptions are the placeholder person
     *       created together with this very account, and a person an earlier verification of this
     *       same link created — nobody yet, so nobody to impersonate.</li>
     * </ul>
     * A row reopened for a re-hire remembers its former person: when they still hold the number they
     * are that person again, never a dormant holder to be released.
     * The addresses come from the ACCOUNT, never the caller, so this cannot verify a code against an
     * address of the caller's choosing.
     */
    @Override
    @Transactional
    public JoinVerification verifyJoinCode(String rawToken, String channel, String code) {
        boolean byMobile = "mobile".equalsIgnoreCase(channel);
        String address = invitationService.resolveJoinChannel(rawToken, byMobile ? "mobile" : "email");
        this.verifyCode(LoginIdentifiers.normalize(address), code);
        UserAccount account = invitationService.resolveJoinAccount(rawToken)
                .orElseThrow(() -> new BusinessException("This invitation link is no longer usable."));
        String mobile = byMobile ? address : account.getMobile();

        if (StringUtils.isBlank(mobile)) {
            // A record from before the personal mobile was required: the email is all there is.
            if (account.getProfileId() == null) {
                return this.verified(rawToken, Objects.requireNonNullElseGet(proofGuard.joinPersonOf(rawToken),
                        () -> this.createJoinPerson(rawToken, address)), "email");
            }
            // The email proves who holds the mailbox, which the company chose; it says nothing about
            // the person the row names. That is enough for the placeholder minted with the account —
            // nobody yet — and never for a real person: a row a tenant pointed at someone else's
            // address and then readdressed to a mailbox of its own would hand that person's
            // identity, in every company, to whoever reads it. Such a row needs a mobile to join.
            if (!invitationService.isPlaceholderPerson(account.getProfileId(), account.getId())) {
                throw AccountErrorCodes.refusal(AccountErrorCodes.JOIN_NEEDS_MOBILE,
                        "This invitation needs a mobile number to confirm who you are. Please contact your HR.");
            }
            return this.verified(rawToken, account.getProfileId(), "email");
        }
        Long createdForThisLink = proofGuard.joinPersonOf(rawToken);
        UserIdentity holder = this.holderOf(mobile, account, createdForThisLink);
        if (holder == null) {
            return this.verified(rawToken, this.createJoinPerson(rawToken, mobile), byMobile ? "mobile" : "email");
        }
        if (byMobile || this.isPlaceholderFor(holder, account)
                || Objects.equals(holder.getProfileId(), createdForThisLink)) {
            return this.verified(rawToken, holder.getProfileId(), byMobile ? "mobile" : "email");
        }
        return JoinVerification.verifyMobile(mobileFormat.format(mobile).masked());
    }

    /** A person for this link, remembered so verifying again finds them instead of making another. */
    private Long createJoinPerson(String rawToken, String identifier) {
        Long profileId = profileService.createPersonForJoin(identifier);
        proofGuard.rememberJoinPerson(rawToken, profileId);
        return profileId;
    }

    /**
     * The person whose login mobile this is — none when nobody holds it, or only a dormant person
     * did. Two holders are never dormant here, though they hold no live account: the person this
     * reopened row belonged to (they are coming back), and a person an earlier verification of this
     * same link created (they hold no account until confirmJoin binds one, and releasing their
     * number would orphan them and make yet another person on the next try).
     */
    private UserIdentity holderOf(String mobile, UserAccount account, Long createdForThisLink) {
        UserIdentity holder = identityService.findByLoginIdentifier(LoginIdentifiers.typedForm(mobile)).orElse(null);
        if (holder != null && holder.getProfileId() != null
                && (holder.getProfileId().equals(account.getFormerProfileId())
                        || holder.getProfileId().equals(createdForThisLink))) {
            return holder;
        }
        if (accountService.releaseDormantLoginMobile(mobile, "Join")) {
            return null;
        }
        return holder;
    }

    private boolean isPlaceholderFor(UserIdentity holder, UserAccount account) {
        return Objects.equals(holder.getProfileId(), account.getProfileId())
                && invitationService.isPlaceholderPerson(holder.getProfileId(), account.getId());
    }

    /**
     * The verification result, carrying a freshly minted proof. Minted here and nowhere else: every
     * branch above has just seen the code pass, and the proof must mean exactly that.
     */
    private JoinVerification verified(String rawToken, Long profileId, String channel) {
        // An Unbind & Re-invite link always sets the password: the membership is the person's again
        // the moment it is set, and someone who already had one resets it here.
        if (invitationService.isRebindLink(rawToken)) {
            return JoinVerification.rebindVerified(profileId, proofGuard.mint(rawToken, profileId, channel));
        }
        boolean mustSetPassword = identityService.findByProfile(profileId)
                .map(identity -> StringUtils.isBlank(identity.getPassword())).orElse(true);
        return JoinVerification.verified(profileId, mustSetPassword, proofGuard.mint(rawToken, profileId, channel));
    }

    @Override
    @Transactional
    public JoinPasswordResult setJoinPassword(String rawToken, Long profileId, String newPassword, String proof) {
        // The proof comes FIRST, before anything about the invitation or the person is looked up.
        // This endpoint is anonymous and takes the profileId from the caller; the proof exists only
        // if verifyJoinCode saw the code pass for this invitation AND decided on this person (after
        // the mobile check, when the email alone could not decide). It is left alive on purpose:
        // confirmJoin follows and spends it.
        proofGuard.require(proof, rawToken, profileId);
        UserAccount account = invitationService.resolveJoinAccount(rawToken)
                .orElseThrow(() -> new BusinessException("This invitation link is no longer usable."));
        if (account.getProfileId() != null && !account.getProfileId().equals(profileId)
                && !invitationService.isPlaceholderPerson(account.getProfileId(), account.getId())) {
            throw new BusinessException("This link does not belong to that account.");
        }
        UserIdentity identity = identityService.findByProfile(profileId)
                .orElseThrow(() -> new BusinessException("Person record not found."));
        boolean rebind = invitationService.isRebindLink(rawToken);
        boolean hadPassword = StringUtils.isNotBlank(identity.getPassword());
        // A first-password path, not a reset: someone who already has a password signs in with it.
        // Except on an Unbind & Re-invite link, which resets it on purpose.
        if (hadPassword && !rebind) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.PASSWORD_ALREADY_SET,
                    "A password is already set — sign in with it instead.");
        }
        identityService.setPassword(identity.getId(), newPassword);
        history.record(LoginEvent.PASSWORD_SET, profileId, account.getId(), account.getTenantId(), null, null, null);
        if (!rebind) {
            return JoinPasswordResult.notCompleted();
        }
        // Read before the bind: it spends the proof and the link, after which neither resolves.
        AuthenticationResult.JoinPrefill prefill = this.joinPrefill(rawToken, proof);
        // Already a member: the account is this person's again and active now, with no confirm step.
        invitationService.confirmJoin(rawToken, profileId, proof);
        if (hadPassword) {
            // A replaced password ends every session that knew the old one, and every device's
            // notifications, as a reset does.
            sessionService.revokePerson(profileId, null, SessionRevokeReason.PASSWORD_RESET);
            if (pushDeviceRegistry != null) {
                pushDeviceRegistry.unregisterPerson(profileId);
            }
            securityLog.record(securityLog.entry(SecurityAction.PASSWORD_RESET, SecurityLogSource.WEB,
                    account, profileId));
        }
        return new JoinPasswordResult(true, prefill);
    }

    /**
     * The contact the person verified for this link, in full — only ever to them, so the next screen
     * can prefill the APP's sign-in. Null when the proof names no channel or the link has no such
     * contact.
     */
    private AuthenticationResult.JoinPrefill joinPrefill(String rawToken, String proof) {
        String channel = proofGuard.channelOf(proof);
        if (channel == null) {
            return null;
        }
        JoinContacts contacts = invitationService.resolveJoinContacts(rawToken);
        String value = contacts == null ? null : "mobile".equals(channel) ? contacts.mobile() : contacts.email();
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return new AuthenticationResult.JoinPrefill(channel,
                "mobile".equals(channel) ? LoginIdentifiers.normalize(value) : value);
    }

    @Override
    @Transactional
    public void resetPasswordByCode(String identifier, String code, String newPassword) {
        // Mobile, or the enterprise email of a joined account: the same person either way, and it is
        // their one password that is reset.
        UserIdentity identity = this.requireLinked(identifier);
        // The strength rules first: verifying spends the code, and a weak password refused after it
        // would send the person back for a new one.
        PasswordPolicy.validate(newPassword);
        this.verifyCode(LoginIdentifiers.normalize(identifier), code);
        boolean wasLocked = identityService.isPasswordLocked(identity);
        // Strength rules and the lock reset both live inside setPassword — a reset must clear the
        // lock, or someone who forgot their password stays locked out of the password they just set.
        identityService.setPassword(identity.getId(), newPassword);
        if (wasLocked) {
            history.record(LoginEvent.UNLOCKED, identity.getProfileId(), null, null,
                    LoginChannel.of(identifier), null, null);
        }
        // Whoever knew the old password may be signed in somewhere: every session of every company
        // ends, and no device keeps receiving this person's notifications. There is no "current"
        // session to keep — the person is not signed in.
        sessionService.revokePerson(identity.getProfileId(), null, SessionRevokeReason.PASSWORD_RESET);
        if (pushDeviceRegistry != null) {
            pushDeviceRegistry.unregisterPerson(identity.getProfileId());
        }
        securityLog.record(securityLog.entry(SecurityAction.PASSWORD_RESET,
                ClientInfo.current().isApp() ? SecurityLogSource.APP : SecurityLogSource.WEB,
                null, identity.getProfileId()));
    }

    @Override
    @Transactional
    public AuthenticationResult setFirstPassword(String authToken, String newPassword) {
        // The person comes from the single-use sign-in token, never from the request: this is
        // anonymous, and "set the first password of profile X" must not be callable by anyone.
        return this.spendingPreAuthToken(authToken, profileId -> {
            UserIdentity identity = identityService.findByProfile(profileId)
                    .orElseThrow(() -> new BusinessException("Person record not found."));
            if (StringUtils.isNotBlank(identity.getPassword())) {
                throw AccountErrorCodes.refusal(AccountErrorCodes.PASSWORD_ALREADY_SET,
                        "A password is already set — sign in with it instead.");
            }
            // Strength rules live inside setPassword, checked against this person's own contacts.
            identityService.setPassword(identity.getId(), newPassword);
            history.record(LoginEvent.PASSWORD_SET, profileId, null, null, null, null, null);
            identity.setPassword("set");
            // Now the entry rule decides where to go, exactly as if they had signed in with a password.
            return this.afterAuthentication(identity);
        });
    }

    @Override
    public boolean mustSetPassword(Long profileId) {
        return identityService.findByProfile(profileId)
                .map(this::mustSetPasswordFor)
                // Unknown person → do not claim they are fine; the caller fails elsewhere.
                .orElse(Boolean.FALSE);
    }

    /**
     * Whether this person must set a password before going any further.
     *
     * <p>One rule, reached from both the id-only overload and {@link #afterAuthentication}, which
     * holds the identity already and would otherwise pay a second read for it. It used to be inlined
     * there as a bare blank-password test — so the exemption below applied when switching tenant and
     * not when signing in, which is the one path every consultant takes.
     *
     * <p>Consultants are exempt. An employee is forced because they arrived by invitation
     * and, without a password, could not come back through the password route at all. A consultant is
     * created by the platform with no invitation and no welcome mail, and code login is their intended
     * way in for as long as they like — forcing the step would block a login on a credential nobody
     * asked them to create. They may still set one from Personal Settings, so this exempts them from
     * being FORCED, not from having one.
     */
    private boolean mustSetPasswordFor(UserIdentity identity) {
        if (StringUtils.isNotBlank(identity.getPassword())) {
            return false;   // asked first: the common answer, and it costs no read
        }
        Long profileId = identity.getProfileId();
        return !holdsOnlyConsultantMemberships(profileId, heldMemberships(profileId));
    }

    /**
     * The memberships this person actually HOLDS — an invitation nobody has accepted is not one.
     *
     * <p>Only the forcing rule narrows this far, and it has to. A row HR has created but the person
     * has never accepted carries {@code consultant = null}, because nothing has decided yet which
     * hat it is; {@code Boolean.TRUE.equals(null)} is false, so a single outstanding invitation
     * anywhere defeated the consultant exemption and walled a consultant behind Set Password — the
     * invitation did not even have to be for an employee seat, since an invitation to consult
     * elsewhere looks exactly the same here. An invitation that is never accepted never expires
     * either, so the exemption was not postponed, it was gone.
     *
     * <p>The statuses are the picker's ({@link #COUNTED_STATUSES}), and that is the point: this
     * class already decided there what counts as a membership somebody holds, and the two rules
     * disagreeing is how one of them ended up wrong.
     *
     * <p>Not applied to {@link #noCompanyRefusal}, which deliberately asks the wider set: it runs
     * on the path that is already refusing, and the unaccepted invitation is exactly what it needs
     * to see in order to say "open the link we sent you" rather than something the person cannot
     * act on.
     */
    private List<UserAccount> heldMemberships(Long profileId) {
        return accountService.listMembershipsOf(profileId).stream()
                // Null-checked first: COUNTED_STATUSES is a Set.of, whose contains(null) THROWS
                // rather than answering false. A row with no status is not one anybody holds, so it
                // drops out here either way — but it must not take the login down on its way.
                .filter(account -> account.getStatus() != null
                        && COUNTED_STATUSES.contains(account.getStatus()))
                .toList();
    }

    /**
     * Whether every membership this person holds is a consultant's.
     *
     * <p>The question the two rules below actually want, and not the same as "is this person a
     * consultant". Somebody can be an employee at one company and a consultant for another — the
     * design says so explicitly — and each hat carries its own rule. The exemptions are written
     * against the whole person because both are asked before a company is chosen, so the safe
     * reading is the one that keeps the employee half intact: a dual-hat person is still forced to
     * set a password, and is still told what an employee would be told.
     *
     * <p>An empty list answers true for a consultant, which is the state a consultant whose grants
     * have all lapsed is in — exactly the case the wording exists for.
     */
    private boolean holdsOnlyConsultantMemberships(Long profileId, List<UserAccount> memberships) {
        return consultantService.isConsultant(profileId)
                && memberships.stream().allMatch(account -> Boolean.TRUE.equals(account.getConsultant()));
    }

    @Override
    public List<MembershipOption> listTenants(String authToken) {
        // Reads the person from the token, not the request: listing another person's tenants is
        // a smaller leak than taking over their session, but it is the same unauthenticated call.
        return resolveMemberships(resolvePreAuthToken(authToken));
    }

    /**
     * Why a row cannot be entered even though the membership behind it is intact.
     *
     * <p>Asked for consultant rows only — see the call site — and about one thing only: whether the
     * company itself is available today. One neutral phrase rather than the tenant's
     * actual status: whether a customer is suspended or closed is that customer's business, and the
     * person reading this row needs to know they cannot go in, not why the company stopped paying.
     */
    private String unavailableReason(Long tenantId) {
        if (tenantInfoService == null || tenantInfoService.isTenantActive(tenantId)) {
            return null;
        }
        return "Unavailable";
    }

    private List<MembershipOption> resolveMemberships(Long profileId) {
        return this.resolveMemberships(profileId, accountService.listMembershipsOf(profileId));
    }

    private List<MembershipOption> resolveMemberships(Long profileId, List<UserAccount> memberships) {
        // The lock lives on the person's credential, so it is read once and stamped on every
        // option rather than looked up per company. So does "the company entered last time".
        Optional<UserIdentity> identity = identityService.findByProfile(profileId);
        boolean locked = identity.map(identityService::isPasswordLocked).orElse(false);
        Long lastAccountId = identity.map(UserIdentity::getLastAccountId).orElse(null);
        // The APP is for employees: a consultancy or a platform account is never offered there, only
        // on the web.
        boolean app = ClientInfo.current().isApp();
        return memberships.stream()
                .filter(account -> !app || webOnlyRefusal(account) == null)
                // Two kinds of membership, two rules. An EMPLOYMENT that cannot be entered is still
                // shown greyed — it is a standing relationship the person can ask about. A
                // CONSULTANCY that is off is simply absent, whichever side turned it off: a lapsed
                // grant is access they no longer have, and an account the customer suspended is the
                // customer saying not now. Listing either would invite them to ask a tenant about a
                // decision that tenant did not make, or has deliberately made. That is grantStands, not canEnter: canEnter also folds in the COMPANY's own
                // state, and a live grant into a frozen company is the one consultant row that does
                // stay — greyed, carrying the reason (the picker's row rules and the frozen-company
                // rule are about different causes, not in conflict).
                .filter(account -> Boolean.TRUE.equals(account.getConsultant())
                        ? consultantService.grantStands(profileId, account.getTenantId())
                                && account.getStatus() == AccountStatus.ACTIVE
                        : account.getStatus() != null && COUNTED_STATUSES.contains(account.getStatus()))
                .map(account -> new MembershipOption(
                        account.getId(), account.getTenantId(),
                        tenantInfoService == null ? null
                                : tenantInfoService.getTenantName(account.getTenantId()),
                        displayStatus(account.getStatus(), locked),
                        locked, Boolean.TRUE.equals(account.getConsultant()),
                        // Every row, employment included: an Active account in a company the
                        // platform has suspended cannot be entered either, and left selectable it
                        // was entered automatically as the company used last time and refused on
                        // the spot — every sign-in failed, with the person's other companies never
                        // offered.
                        unavailableReason(account.getTenantId()),
                        Objects.equals(account.getId(), lastAccountId)))
                // Selectable first: the common case is one usable company among some frozen ones,
                // and making the person hunt for it in a mixed list is a needless step.
                .sorted(Comparator.comparing(MembershipOption::selectable).reversed())
                .toList();
    }

    /**
     * The status a person sees for a membership. Locked is not stored: an Active membership of a
     * person whose password login is locked reads as Locked (a Frozen one stays Frozen).
     */
    static AccountStatus displayStatus(AccountStatus stored, boolean personLocked) {
        if (stored == AccountStatus.ACTIVE && personLocked) {
            return AccountStatus.LOCKED;
        }
        return stored;
    }

    /**
     * The entry rule, decided here once so the APP and Web do not each compute it:
     * counting the Active, Locked and Frozen memberships — exactly {@code options} —
     * <ol>
     *   <li>one in total, and it can be entered → enter it;</li>
     *   <li>the company entered last time can still be entered → enter it;</li>
     *   <li>otherwise → the picker (null).</li>
     * </ol>
     */
    static Long autoEnterAccount(List<MembershipOption> options) {
        List<MembershipOption> enterable = options.stream().filter(MembershipOption::selectable).toList();
        if (options.size() == 1 && enterable.size() == 1) {
            return enterable.getFirst().accountId();
        }
        return enterable.stream().filter(MembershipOption::lastUsed)
                .map(MembershipOption::accountId).findFirst().orElse(null);
    }

    /**
     * Why there is no company to show, or null when there is one — enterable or not. A person whose
     * only companies are Frozen is shown them, greyed with their badge, so they can see they were
     * frozen; the reason is for having nothing to list at all: NOT_JOINED when the person holds
     * nothing but unconfirmed invitations, DEACTIVATED otherwise (every account closed, or none).
     */
    private static AuthenticationResult.NoEnterableReason noEnterableReason(List<UserAccount> memberships,
                                                                            List<MembershipOption> options) {
        if (!options.isEmpty()) {
            return null;
        }
        boolean onlyInvitations = !memberships.isEmpty()
                && memberships.stream()
                        .allMatch(account -> account.getStatus() == AccountStatus.PENDING
                                || account.getStatus() == AccountStatus.INVITED);
        return onlyInvitations ? AuthenticationResult.NoEnterableReason.NOT_JOINED
                : AuthenticationResult.NoEnterableReason.DEACTIVATED;
    }

    /**
     * Why an authenticated person has nowhere to go — worded from the reason.
     *
     * <p>Someone holding nothing but an unaccepted invitation CAN authenticate (their identity is
     * seeded when HR creates the account), so they do reach this refusal, and "not linked to any
     * company — contact your administrator" is both wrong and unactionable for them: the administrator already did their part.
     * The wider set is asked for only here, on the path that is already refusing, so the normal
     * login pays nothing for the distinction.
     */
    private BusinessException noCompanyRefusal(Long profileId) {
        // Asked first: a consultant with no live grant is not an employee with no company, and the
        // generic wording would send them to a tenant administrator who cannot help. Only when the
        // person is a consultant AND holds no employment does this apply — someone who is both gets
        // the employee wording, which is the half they can still act on.
        List<UserAccount> memberships = accountService.listMembershipsOf(profileId);
        if (holdsOnlyConsultantMemberships(profileId, memberships)) {
            return AccountErrorCodes.refusal(AccountErrorCodes.CONSULTANT_NO_ACCESS, CONSULTANT_NO_ACCESS_MESSAGE);
        }
        boolean invited = memberships.stream()
                .anyMatch(account -> account.getStatus() == AccountStatus.PENDING
                        || account.getStatus() == AccountStatus.INVITED);
        return invited
                ? AccountErrorCodes.refusal(AccountErrorCodes.INVITATION_PENDING, INVITATION_PENDING_MESSAGE)
                : AccountErrorCodes.refusal(AccountErrorCodes.NO_COMPANY, NO_COMPANY_MESSAGE);
    }

    @Override
    public Long resolveSingleMembership(Long profileId) {
        List<MembershipOption> options = this.resolveMemberships(profileId);
        if (options.isEmpty()) {
            // Authenticated, but a member of nothing to enter — off-boarded everywhere, a profile
            // with no membership yet, or nothing but unaccepted invitations. Either way there is
            // nowhere to go, and which of them it is decides what the person is told.
            throw noCompanyRefusal(profileId);
        }
        List<MembershipOption> selectable = options.stream().filter(MembershipOption::selectable).toList();
        if (selectable.size() == 1 && options.size() == 1) {
            return selectable.get(0).accountId();
        }
        // More than one, or exactly one that is not enterable: both need the picker. Refusing here
        // rather than auto-picking is deliberate — silently choosing for someone who belongs to two
        // tenants puts them in a workspace they did not ask for, and the wrong one is worse than
        // an extra click.
        throw new MultipleMembershipsException(options);
    }

    @Override
    public AuthenticationResult selectTenant(String authToken, Long accountId) {
        // Spent only once the session is actually minted: a failed attempt puts the token back, so
        // "could not enter that company" is followed by a retry, not by signing in from the start.
        return this.spendingPreAuthToken(authToken, profileId -> this.enter(profileId, accountId));
    }

    private AuthenticationResult enter(Long profileId, Long accountId) {
        this.refuseConsultancyOnApp(accountId);
        MembershipOption chosen = this.resolveMemberships(profileId).stream()
                .filter(option -> option.accountId().equals(accountId))
                .findFirst()
                // Ownership check: the membership must be one the token's person actually holds, or
                // naming any accountId would mint a session in a company they are not a member of.
                .orElseThrow(() -> new BusinessException("That company is not available for your account."));
        if (!chosen.selectable()) {
            throw orgUnavailable(deniedMessage(chosen));
        }
        boolean mustSetPassword = this.mustSetPassword(profileId);
        // The first password is owed before any session: picking a company does not skip it.
        if (mustSetPassword) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.PASSWORD_REQUIRED,
                    "Set your password before entering a company.");
        }
        return AuthenticationResult.resolved(profileId, profileService.getUserInfo(accountId), mustSetPassword);
    }

    @Override
    public List<MembershipOption> myTenants(Long currentAccountId) {
        return this.resolveMemberships(personBehind(currentAccountId));
    }

    @Override
    public AuthenticationResult switchTenant(Long currentAccountId, Long accountId) {
        Long profileId = personBehind(currentAccountId);
        this.refuseConsultancyOnApp(accountId);
        MembershipOption chosen = this.resolveMemberships(profileId).stream()
                .filter(option -> option.accountId().equals(accountId))
                .findFirst()
                // Same ownership check selectTenant makes, and it is the whole gate here: the
                // caller names a company, and nothing else about it is theirs to assert.
                .orElseThrow(() -> new BusinessException("That company is not available for your account."));
        if (!chosen.selectable()) {
            throw orgUnavailable(deniedMessage(chosen));
        }
        // No session minted here — the controller does that, and only after generateSessionId has
        // run the tenant and account gates on the TARGET membership.
        return AuthenticationResult.resolved(
                profileId, profileService.getUserInfo(accountId), this.mustSetPassword(profileId));
    }

    @Override
    public AuthenticationResult leaveTenant(Long currentAccountId) {
        Long profileId = personBehind(currentAccountId);
        List<MembershipOption> options = this.resolveMemberships(profileId);
        // Nothing left to choose from: the same answer a login with nowhere to go gets, worded for
        // who this is — a consultant whose grants have all lapsed is told to ask the platform,
        // not "your administrator".
        if (options.isEmpty()) {
            throw noCompanyRefusal(profileId);
        }
        // Always the picker, even with a single company left. The person was just removed from
        // somewhere; walking them silently into somewhere else would read as the refusal having
        // failed, and the picker is where the reason is shown.
        return AuthenticationResult.choicePending(
                profileId, options, this.mustSetPassword(profileId), issuePreAuthToken(profileId));
    }

    /**
     * The person behind a session: the session maps to a UserAccount (one membership), and every
     * membership question is asked of the PERSON, so the hop through {@code profileId} is what
     * turns "where you are" back into "who you are".
     *
     * <p>A session pointing at an account that is gone, or at one never paired with a person, is a
     * broken session rather than a business refusal — answered with the same message the framework
     * gives any request carrying one.
     */
    private Long personBehind(Long accountId) {
        Long profileId = accountService.getById(accountId).map(UserAccount::getProfileId).orElse(null);
        if (profileId == null) {
            throw new UserNotFoundException("Invalid session ID");
        }
        return profileId;
    }

    /**
     * Issue a session for an account: the gates, then the session itself, then remember the company
     * as the person's last one for the next login's entry rule.
     *
     * @param userId the account to enter
     * @return the session id, which is also the session token the APP sends as X-Session-Id
     */
    @Override
    public String generateSessionId(Long userId) {
        // Tenant + account lifecycle gates — the single choke point every login flow
        // passes through, and it runs AFTER credentials are verified.
        validateTenantActive(userId);
        UserAccount account = validateAccountActive(userId);
        ClientInfo client = ClientInfo.current();
        // The one choke point every session passes, so no route into the APP is left open to a
        // consultancy or a platform account — whatever picked it.
        BusinessException webOnly = client.isApp() ? webOnlyRefusal(account) : null;
        if (webOnly != null) {
            throw webOnly;
        }
        String sessionId = sessionStore.create(account.getId(), account.getProfileId(), account.getTenantId(),
                client.platform(), client.appVersion(), client.deviceModel());
        this.rememberLastAccount(account);
        return sessionId;
    }

    /** "The company entered last time" for the entry rule — on the person, so it spans devices. */
    private void rememberLastAccount(UserAccount account) {
        if (account.getProfileId() == null) {
            return;
        }
        identityService.findByProfile(account.getProfileId())
                .filter(identity -> !Objects.equals(identity.getLastAccountId(), account.getId()))
                .ifPresent(identity -> {
                    UserIdentity patch = new UserIdentity();
                    patch.setId(identity.getId());
                    patch.setLastAccountId(account.getId());
                    identityService.updateOne(patch);
                });
    }

    /**
     * Forgot password — issue a self-service PASSWORD_RESET token and email the set-password link.
     * Delegates to {@link UserInvitationService}; silently no-ops for an unknown email (no
     * account enumeration).
     */
    @Override
    public void forgetPassword(String email) {
        invitationService.forgotPassword(email);
    }

    /**
     * Set the password via a token — serves both invitation-accept and forgot-password reset.
     * Delegates to {@link UserInvitationService} (validates the token, sets a fresh salted hash,
     * and activates an INVITED account).
     */
    @Override
    @Transactional
    public void resetPassword(String token, String newPassword) {
        invitationService.acceptToken(token, newPassword);
    }

    /** Validate a token for the public set-password page. */
    @Override
    public InvitationInfo inviteInfo(String token) {
        return invitationService.inspectToken(token);
    }

}