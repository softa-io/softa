package io.softa.starter.user.service.impl;

import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import io.softa.framework.base.utils.RandomUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import io.softa.framework.base.config.SystemConfig;
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
public class LoginServiceImpl implements LoginService {

    /** Mirrors UserIdentityServiceImpl's window, for the message the locked-out person sees. */
    private static final int LOCK_MINUTES = 30;

    /**
     * The memberships the company step counts (PRD 1.5 step 3: Active + Frozen + Locked).
     *
     * <p>An INVITED or PENDING row is a membership HR has created, not one the person holds yet:
     * counting it sends someone with one real company and one open invitation to the picker the PRD
     * says they should skip, and the picker then lists a company they have not accepted into.
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
     * The first wrong password that is answered with a remaining-attempts count (PRD L3). Early
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

    @Autowired
    private UserInvitationService invitationService;

    /** Send / attempt limits for one-time codes (PRD D2) — see the class for why each exists. */
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

    /** A company that cannot be entered right now, with the code the APP re-runs the entry rule on. */
    private static BusinessException orgUnavailable(String message) {
        BusinessException e = new BusinessException(message);
        e.withErrorCode(SessionErrorCodes.ORG_UNAVAILABLE);
        return e;
    }

    /** Human-readable reason for refusing login to a non-ACTIVE account. */
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
        String code = RandomStringUtils.insecure()
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
     * <p>Said out loud (PRD login): the screen tells a mistyped number "contact HR" instead of
     * pretending a code is on its way. That makes sending and signing in an answer to "is this
     * number an employee's?"; the send limits in {@link VerificationCodeGuard} are what bound it.
     * The lookup gets the TYPED form, as every identifier lookup here does (see
     * {@link LoginIdentifiers}).
     */
    private UserIdentity requireLinked(String identifier) {
        return identityService.findByLoginIdentifier(LoginIdentifiers.typedForm(identifier))
                .filter(identity -> !accountService.listAccountIdsOf(identity.getProfileId()).isEmpty())
                .orElseThrow(() -> AccountErrorCodes.refusal(AccountErrorCodes.ACCOUNT_NOT_FOUND,
                        "This account does not exist. Please contact your HR."));
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

    /**
     * Refuse to identify anyone from a contact shared by more than one account (finding #2).
     *
     * <p>A verification code proves control of the ADDRESS, not of a person. A work number used by
     * several employees identifies none of them, so resolving it to whichever person happens to
     * hold it as their login identifier lets a second holder sign in — or reset the password — as
     * the first. Every code-and-identifier entry point (login, /join, reset) has to ask this;
     * the password path does not, because knowing the password is itself the proof a shared
     * contact cannot supply.
     */
    private void assertContactNotShared(String identifier) {
        if (accountService.isWorkContactShared(identifier)) {
            throw new BusinessException("This contact is shared by more than one account, so we "
                    + "cannot confirm who you are. Please contact your administrator.");
        }
    }

    @Override
    public AuthenticationResult authenticateByCode(String identifier, String code) {
        // The code is keyed by the canonical form; the lookups get the typed form, so a row seeded
        // with the separators the person still types is found too (LoginIdentifiers.loginSpellings).
        LoginChannel channel = LoginChannel.of(identifier);
        LoginMethod method = channel == LoginChannel.EMAIL ? LoginMethod.EMAIL_CODE : LoginMethod.SMS_CODE;
        UserIdentity identity = this.requireLinked(identifier);
        try {
            verifyCode(LoginIdentifiers.normalize(identifier), code);
        } catch (BusinessException e) {
            history.record(LoginEvent.LOGIN_FAILED, identity.getProfileId(), null, null, channel, method,
                    StringUtils.defaultString(e.getErrorCode(), e.getMessage()));
            throw e;
        }
        // A lock refuses the PASSWORD only; a code still signs the person in, and into every
        // company, with nothing reduced (PRD login D31, §5.3).
        return this.signedIn(identity, channel, method);
    }

    @Override
    public AuthenticationResult authenticateByPassword(String identifier, String password) {
        LoginChannel channel = LoginChannel.of(identifier);
        UserIdentity identity = this.requireLinked(identifier);
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
            // the first password attempt after it.
            history.record(LoginEvent.UNLOCKED, profileId, null, null, channel, null, null);
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
        AuthenticationResult result = this.afterAuthentication(identity);
        UserInfo entered = result.userInfo();
        history.record(LoginEvent.LOGIN_SUCCESS, identity.getProfileId(),
                entered == null ? null : entered.getUserId(), entered == null ? null : entered.getTenantId(),
                channel, method, null);
        return result;
    }

    private static String lockedMessage() {
        return "Too many failed attempts. Password login is locked for " + LOCK_MINUTES
                + " minutes. You can still log in with a verification code.";
    }

    /**
     * The refusal for the {@code failures}-th consecutive wrong password. From the seventh it names
     * the attempts left (PRD L3) — early guesses get the bare refusal, because a countdown from the
     * first attempt tells whoever is guessing exactly how many tries they have left.
     */
    private static BusinessException wrongPassword(long failures) {
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
        List<MembershipOption> options = this.resolveMemberships(profileId);
        if (options.isEmpty() && holdsOnlyConsultantMemberships(profileId, accountService.listMembershipsOf(profileId))) {
            // A consultant whose grants have all lapsed is not an employee with no company: the
            // platform, not a tenant, is who can help, and the two reasons below cannot say that.
            throw new BusinessException(CONSULTANT_NO_ACCESS_MESSAGE);
        }
        Long autoEnter = autoEnterAccount(options);
        // A password owed comes first (PRD login §3): no session until it is set, even when the
        // entry rule already knows where the person will go — setFirstPassword enters it after.
        if (autoEnter != null && !mustSetPassword) {
            return AuthenticationResult.resolved(profileId, profileService.getUserInfo(autoEnter), false);
        }
        AuthenticationResult.NoEnterableReason reason = this.noEnterableReason(profileId, options);
        // A choice is pending (or a password is), so authentication succeeded but no session is
        // issued yet. Mint a single-use token proving THIS person just authenticated; selectTenant
        // and setFirstPassword read the person from it, never from a client-supplied id — otherwise
        // the next step would be an unauthenticated "issue me a session for profileId X". Nobody
        // needs one with nowhere to go and nothing owed.
        String token = reason == null || mustSetPassword ? issuePreAuthToken(profileId) : null;
        return AuthenticationResult.choicePending(profileId, options, mustSetPassword, token, autoEnter, reason);
    }

    private static String preAuthKey(String token) {
        return "login:preauth:" + token;
    }

    private String issuePreAuthToken(Long profileId) {
        String token = RandomUtils.randomString(PREAUTH_TOKEN_BYTES);
        cacheService.save(preAuthKey(token), profileId.toString(), PREAUTH_TTL_SECONDS);
        return token;
    }

    /** The person a live pre-auth token stands for, or a refusal if it expired / never existed. */
    private Long resolvePreAuthToken(String authToken) {
        String value = StringUtils.isBlank(authToken) ? null : cacheService.get(preAuthKey(authToken));
        if (value == null) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.AUTH_TOKEN_EXPIRED,
                    "Your sign-in step expired. Please log in again.");
        }
        return Long.valueOf(value);
    }


    @Override
    public AuthenticationResult confirmJoin(String rawToken, Long profileId, String proof) {
        // Read BEFORE the membership activates: accepting spends the invitation, and neither the
        // row's prior binding nor the invitation's contacts can be resolved from the token after.
        // An unusable token resolves to nothing here and is then judged by the invitation service
        // itself (a refusal, or the quiet re-entrant return), exactly as before.
        Optional<UserAccount> joining = invitationService.resolveJoinAccount(rawToken);
        boolean boundBefore = joining.map(account -> account.getProfileId() != null).orElse(false);
        JoinContacts contacts = boundBefore ? invitationService.resolveJoinContacts(rawToken) : null;

        invitationService.confirmJoin(rawToken, profileId, proof);

        // The identity, not the profile: what decides the next step is whether a password exists,
        // and that lives on the credential.
        UserIdentity identity = identityService.findByProfile(profileId)
                .orElseThrow(() -> new BusinessException("Person record not found."));
        // Two different things were proven, and only one of them is this person. The code proved
        // control of the WORK address on a row that already named its person — a mailbox the
        // company holds and reissues. For an unbound row the address IS the person's own login
        // identifier (the contact tie), so the same code does identify them; for a bound row it
        // identifies whoever holds the mailbox. So the membership stays activated — HR intended it
        // — and nothing that stands for the person is issued: no session, and no pre-auth token
        // into their other tenants. Whether the identity has a password does not enter into it:
        // a password-less identity would have its GLOBAL first password minted from the session
        // (/UserAccount/setMyFirstPassword), and an identity WITH a password would simply be
        // impersonated — in this company and, through the company step, in every other one it
        // belongs to — by whoever holds the mailbox. A person who has their own way in must use it;
        // the one who has none (nothing outside the invitation's contacts) has the address as the
        // only evidence there is, and for them the session is issued as before.
        // Residual, by design: for that fully released identity the session still rests on HR
        // having addressed the invitation to the right person.
        if (boundBefore && holdsLoginOutsideInvitation(identity, contacts)) {
            return AuthenticationResult.requireSignIn();
        }
        // Straight into the company just joined (PRD invitation flow), whatever else the person
        // belongs to. A re-entrant confirm (the link already spent) has no row to name, and falls
        // back to the ordinary entry rule.
        if (joining.isPresent()) {
            return AuthenticationResult.resolved(profileId,
                    profileService.getUserInfo(joining.get().getId()), mustSetPasswordFor(identity));
        }
        return this.afterAuthentication(identity);
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

    /*
     * Residual, by design — the reissued contact on a FULLY released identity. For a bound row this
     * rebinds the address that received the code onto the person's identity only when that identity
     * holds no login identifier at all (reclaimLoginIdentifier). In that one case the address is the
     * only evidence of who the person is, so whoever now holds a reissued address — a pool phone
     * handed to the next hire, a mailbox HR mistyped onto the revived row — passes the code, gets the
     * address as a login identifier, and from then on signs in by code into the leaver's profile and
     * every company it still belongs to. Not closed here because nothing in the data distinguishes
     * the returning leaver from the stranger; the mitigations are procedural and sit with HR: correct
     * the row's contacts through Reset User BEFORE inviting, and only HR can issue the invitation.
     * The same note stands on UserAccountServiceImpl.rehire, where the row is reopened.
     */
    @Override
    @Transactional
    public JoinVerification verifyJoinCode(String rawToken, String channel, String code) {
        // Resolving the address from the token (not from the caller) is what keeps this from being
        // a way to verify a code against an address of the caller's choosing.
        // Normalised because the invitation stores the WORK contact as HR typed it, while the code
        // was keyed by (sendEmailCode) and the identity is looked up / seeded with the login form.
        // The typed form — what HR wrote on the invitation, lowercased — is what the lookups get,
        // so a row seeded before the separator fold is still the one they find.
        String typedAddress = LoginIdentifiers.typedForm(invitationService.resolveJoinChannel(rawToken, channel));
        String address = LoginIdentifiers.normalize(typedAddress);
        this.verifyCode(address, code);

        // A shared work contact identifies no one, so an invitee holding it cannot be resolved
        // automatically — HR completes the bind. Same guard as login and reset.
        assertContactNotShared(typedAddress);

        // A membership that already belongs to a person — a re-hired leaver's revived row — has
        // answered "who is this?" before the address is consulted. Their work address was released
        // from their identity at off-boarding, so find-or-create by address would see nobody, mint
        // a second person, and confirmJoin would hand the row to it: the real person keeps their
        // password and their other tenants on a profileId nothing points at any more.
        UserAccount account = invitationService.resolveJoinAccount(rawToken)
                .orElseThrow(() -> new BusinessException("This invitation link is no longer usable."));
        if (account.getProfileId() != null) {
            UserIdentity known = identityService.findByProfile(account.getProfileId())
                    .orElseThrow(() -> new BusinessException("Person record not found."));
            // The address comes home as a LOGIN identifier only when the identity is fully released;
            // otherwise the person keeps signing in with what they hold and confirmJoin admits them
            // on the row's profileId. See reclaimLoginIdentifier for the takeover this prevents.
            this.reclaimLoginIdentifier(known, address, typedAddress);
            // No password step here for a person who can already sign in some other way — see
            // holdsLoginOutsideInvitation. They confirm on the row's profileId, confirmJoin answers
            // signInRequired rather than a session, and they set the password in-session after
            // signing in with what they hold — where they are themselves and not merely whoever
            // received this link.
            boolean mustSetPassword = StringUtils.isBlank(known.getPassword())
                    && !holdsLoginOutsideInvitation(known, invitationService.resolveJoinContacts(rawToken));
            return this.verified(rawToken, known.getProfileId(), mustSetPassword);
        }

        // Find-or-create by the address the invitation was sent to. Found = this person already
        // works somewhere and is being added to a second company, and they keep ONE person record
        // (that is the whole point of the global profile). Not found = their first company.
        Optional<UserIdentity> existing = identityService.findByLoginIdentifier(typedAddress);
        if (existing.isPresent()) {
            return this.verified(rawToken, existing.get().getProfileId(),
                    StringUtils.isBlank(existing.get().getPassword()));
        }
        // Brand new person: no password by construction, so the password step always follows.
        // Constructed through the profile service, which is the one waived choke point where a
        // person and their credentials row are minted together.
        return this.verified(rawToken, profileService.createPersonForJoin(address), true);
    }

    /**
     * The verification result, carrying a freshly minted proof. Minted here and nowhere else: every
     * branch above has just seen the code pass, and the proof must mean exactly that.
     */
    private JoinVerification verified(String rawToken, Long profileId, boolean mustSetPassword) {
        return new JoinVerification(profileId, mustSetPassword, proofGuard.mint(rawToken, profileId));
    }

    /**
     * Whether the identity holds a live login identifier the invitation was NOT addressed to.
     *
     * <p>That identifier is a way in the link-holder does not have, and it is what keeps a bound
     * row's first password — and, at confirmJoin, any session at all — off the anonymous path.
     * The code sent to a work address proves control
     * of that address — a mailbox the company holds and reissues — not of the person; for a bound
     * row the row's profileId then names the person, so a company holding a re-hired leaver's work
     * mailbox would be setting a password on an identity that is not theirs, valid at every other
     * company that identity belongs to. Sent instead to sign in with what they hold (by code, since
     * there is no password yet) and set the password from inside that session. An identity holding
     * nothing outside the invitation's contacts has no such way in, so for it the address remains
     * the best evidence available and the password step stays open — the fully released re-hire.
     */
    private static boolean holdsLoginOutsideInvitation(UserIdentity identity, JoinContacts contacts) {
        return (StringUtils.isNotBlank(identity.getLoginEmail()) && !contacts.includes(identity.getLoginEmail()))
                || (StringUtils.isNotBlank(identity.getLoginMobile()) && !contacts.includes(identity.getLoginMobile()));
    }

    /**
     * Put a verified address back onto the person's identity as a login identifier, when it can be.
     *
     * <p>This is the released work contact coming home: off-boarding cleared it so the address could
     * be reissued, and — when the identity holds nothing else — the person who just proved control
     * of it through a code is the one who lost it. Bound only if the identity is fully released and
     * nobody else has claimed the address meanwhile — {@code isIdentifierClaimable} answers that,
     * and when it says no the identity is left as it was rather than made ambiguous.
     *
     * @param address      the canonical form, which is what is written
     * @param typedAddress the form the invitation carries, so the claim check also sees a pre-fold
     *                     row holding the number with its separators
     */
    private void reclaimLoginIdentifier(UserIdentity identity, String address, String typedAddress) {
        if (StringUtils.isNotBlank(identity.getLoginEmail())
                || StringUtils.isNotBlank(identity.getLoginMobile())) {
            // Identity takeover, refused. The row is bound, so the code proved control of a WORK
            // contact that is not currently anyone's login identifier — and a work contact is
            // reissued: a pool phone handed to the next hire, an address HR mistyped onto the
            // revived row. Whoever now physically holds it can pass the code, and rebinding it here
            // would make it a LOGIN identifier for the leaver's identity: from then on the stranger
            // signs in BY CODE to that address and lands in the leaver's profile, with every company
            // the leaver still belongs to. While the identity holds any live identifier, the person
            // has a way in that the stranger does not — they keep signing in with what they have,
            // and confirmJoin admits the bound person on the row's own profileId rather than on this
            // contact. Both channels are checked, not just the one the address would land on: a
            // held mobile proves the person is reachable exactly as much as a held email does.
            return;
        }
        // Fully released: no identifier on either channel. Nobody else can prove ownership of this
        // person's login (there is nothing left to send a code to), and the row's contacts were the
        // person's own at off-boarding, so the one address that reaches the row is the best evidence
        // available of who this is. Rebinding it is what lets the returning leaver sign in at all.
        // The residual — a reissued contact reaching a stranger who then claims a fully released
        // identity — is documented on this method's caller; the mitigation is procedural: HR
        // corrects the row's contacts (Reset User) BEFORE inviting, and the invite is HR-initiated.
        boolean isEmail = address.contains("@");
        if (!identityService.isIdentifierClaimable(typedAddress, identity.getProfileId())) {
            return;
        }
        if (isEmail) {
            identity.setLoginEmail(address);
        } else {
            identity.setLoginMobile(address);
        }
        identityService.updateOne(identity);
    }

    @Override
    @Transactional
    public void setJoinPassword(String rawToken, Long profileId, String newPassword, String proof) {
        // The proof comes FIRST, before anything about the invitation or the person is looked up.
        // This endpoint is anonymous, and for a bound row the tie below is the caller-supplied
        // profileId alone — a value readable off the roster. A company holding a re-hired person's
        // work mailbox (where the link lands) could otherwise call this with the token and that id,
        // set the person's GLOBAL password without ever passing the code, and sign in as them at
        // every other company. The proof exists only if verifyJoinCode saw the code pass for this
        // invitation and this person. It is left alive on purpose: confirmJoin follows and spends it.
        proofGuard.require(proof, rawToken, profileId);
        UserAccount account = invitationService.resolveJoinAccount(rawToken)
                .orElseThrow(() -> new BusinessException("This invitation link is no longer usable."));
        UserIdentity identity = identityService.findByProfile(profileId)
                .orElseThrow(() -> new BusinessException("Person record not found."));

        // Both checks matter. The first ties the person to THIS invitation, so holding a link
        // cannot reach an unrelated person. The second keeps it a first-password path rather than
        // a reset — someone who already has a password must prove it, or arrive by code.
        //
        // The tie mirrors confirmJoin's. For a row that already belongs to a person (a re-hired
        // leaver's revived membership) the row's profileId IS the tie: verifyJoinCode returned that
        // person after the code proved control of the address, so any other id here is a stale
        // client or a link-holder choosing one. Tying a bound row to the contact instead dead-ended
        // exactly the person the invitation was for — one who kept a personal login identifier
        // (ada@personal.com) and has no password: verifyJoinCode sent them here, and the identity
        // carried no contact the invitation names, so the password could never be set.
        // For an unbound row the contact is the tie, as at confirmJoin: the person's login
        // identifier must be one the invitation was addressed to.
        JoinContacts contacts = invitationService.resolveJoinContacts(rawToken);
        if (account.getProfileId() != null) {
            if (!account.getProfileId().equals(profileId)) {
                throw new BusinessException("This link does not belong to that account.");
            }
            // Refused even with a valid proof: the proof shows the code passed, and the code only
            // ever proved control of the work address. A person who can sign in another way sets
            // their password from that session — see holdsLoginOutsideInvitation for the takeover
            // this keeps off the anonymous path. verifyJoinCode already answered mustSetPassword=false
            // for this case; the check is repeated here because this endpoint re-accepts its inputs.
            if (holdsLoginOutsideInvitation(identity, contacts)) {
                throw new BusinessException("Sign in with your existing login to set a password.");
            }
        } else if (!contacts.includes(identity.getLoginEmail()) && !contacts.includes(identity.getLoginMobile())) {
            throw new BusinessException("This link does not belong to that account.");
        }
        if (StringUtils.isNotBlank(identity.getPassword())) {
            throw new BusinessException("A password is already set — sign in with it instead.");
        }
        // Strength rules live inside setPassword, checked against this person's own contacts.
        identityService.setPassword(identity.getId(), newPassword);
    }

    @Override
    @Transactional
    public void resetPasswordByCode(String identifier, String code, String newPassword) {
        // Mobile, or the enterprise email of a joined account: the same person either way, and it is
        // their one password that is reset.
        UserIdentity identity = this.requireLinked(identifier);
        this.verifyCode(LoginIdentifiers.normalize(identifier), code);
        boolean wasLocked = identity.getPasswordLockedUntil() != null;
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
        Long profileId = resolvePreAuthToken(authToken);
        UserIdentity identity = identityService.findByProfile(profileId)
                .orElseThrow(() -> new BusinessException("Person record not found."));
        if (StringUtils.isNotBlank(identity.getPassword())) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.PASSWORD_ALREADY_SET,
                    "A password is already set — sign in with it instead.");
        }
        // Strength rules live inside setPassword, checked against this person's own contacts.
        identityService.setPassword(identity.getId(), newPassword);
        cacheService.clear(preAuthKey(authToken));
        history.record(LoginEvent.PASSWORD_SET, profileId, null, null, null, null, null);
        identity.setPassword("set");
        // Now the entry rule decides where to go, exactly as if they had signed in with a password.
        return this.afterAuthentication(identity);
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
        // The lock lives on the person's credential, so it is read once and stamped on every
        // option rather than looked up per company. So does "the company entered last time".
        Optional<UserIdentity> identity = identityService.findByProfile(profileId);
        boolean locked = identity.map(identityService::isPasswordLocked).orElse(false);
        Long lastAccountId = identity.map(UserIdentity::getLastAccountId).orElse(null);
        return accountService.listMembershipsOf(profileId).stream()
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
                        // Consultant rows only. The frozen-company reason is about the consultant's switcher; an
                        // employment into a frozen company already reads as such through its own
                        // status badge, and giving it a second mechanism would change a path this
                        // feature has no business changing.
                        Boolean.TRUE.equals(account.getConsultant())
                                ? unavailableReason(account.getTenantId()) : null,
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
     * The entry rule (PRD login §3), decided here once so the APP and Web do not each compute it:
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
     * Why there is nowhere to enter, or null when somewhere is enterable. NOT_JOINED only when the
     * person holds nothing but unconfirmed invitations; anything else — every membership Frozen or
     * Deactivated — is DEACTIVATED.
     */
    private AuthenticationResult.NoEnterableReason noEnterableReason(Long profileId, List<MembershipOption> options) {
        if (options.stream().anyMatch(MembershipOption::selectable)) {
            return null;
        }
        boolean onlyInvitations = !accountService.listMembershipsOf(profileId).isEmpty()
                && accountService.listMembershipsOf(profileId).stream()
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
            return new BusinessException(CONSULTANT_NO_ACCESS_MESSAGE);
        }
        boolean invited = memberships.stream()
                .anyMatch(account -> account.getStatus() == AccountStatus.PENDING
                        || account.getStatus() == AccountStatus.INVITED);
        return new BusinessException(invited ? INVITATION_PENDING_MESSAGE : NO_COMPANY_MESSAGE);
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
        Long profileId = resolvePreAuthToken(authToken);
        MembershipOption chosen = this.resolveMemberships(profileId).stream()
                .filter(option -> option.accountId().equals(accountId))
                .findFirst()
                // Ownership check: the membership must be one the token's person actually holds, or
                // naming any accountId would mint a session in a company they are not a member of.
                .orElseThrow(() -> new BusinessException("That company is not available for your account."));
        if (!chosen.selectable()) {
            throw orgUnavailable(accountDeniedMessage(chosen.status()));
        }
        AuthenticationResult result = AuthenticationResult.resolved(
                profileId, profileService.getUserInfo(accountId), this.mustSetPassword(profileId));
        // Single use: consume the token so a leaked one cannot be replayed into another session.
        // Consumed only once the session is actually minted, not before: a token burned by a failed
        // attempt leaves the person facing "could not enter that company, please try again" over a
        // step that can no longer succeed — the retry reports the sign-in as expired and they must
        // start the whole login over. A failed attempt mints nothing, so leaving its token alive
        // costs no replay safety.
        cacheService.clear(preAuthKey(authToken));
        return result;
    }

    @Override
    public List<MembershipOption> myTenants(Long currentAccountId) {
        return this.resolveMemberships(personBehind(currentAccountId));
    }

    @Override
    public AuthenticationResult switchTenant(Long currentAccountId, Long accountId) {
        Long profileId = personBehind(currentAccountId);
        MembershipOption chosen = this.resolveMemberships(profileId).stream()
                .filter(option -> option.accountId().equals(accountId))
                .findFirst()
                // Same ownership check selectTenant makes, and it is the whole gate here: the
                // caller names a company, and nothing else about it is theirs to assert.
                .orElseThrow(() -> new BusinessException("That company is not available for your account."));
        if (!chosen.selectable()) {
            throw orgUnavailable(accountDeniedMessage(chosen.status()));
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