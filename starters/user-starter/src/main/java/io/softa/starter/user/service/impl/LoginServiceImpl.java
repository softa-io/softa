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
import io.softa.starter.user.util.LoginIdentifiers;

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
    private io.softa.starter.user.service.ConsultantService consultantService;

    /** Carries "the code was passed" into the anonymous set-password / confirm steps. */
    @Autowired
    private JoinProofGuard proofGuard;

    @Autowired
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

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
    private void validateAccountActive(Long userId) {
        UserAccount account = accountService.getById(userId)
                .orElseThrow(() -> new BusinessException("Login denied: account not found."));
        if (account.getStatus() == AccountStatus.ACTIVE) {
            return;
        }
        throw new BusinessException(accountDeniedMessage(account.getStatus()));
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

    @Override
    public void sendMobileCode(String mobile) {
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
        String typed = LoginIdentifiers.typedForm(identifier);
        identifier = LoginIdentifiers.normalize(identifier);
        verifyCode(identifier, code);
        assertContactNotShared(typed);
        // Resolved by LOGIN IDENTIFIER on the person, not by a company's work contact: the code
        // was sent to an identifier, and that identifier is what identifies the human being.
        return this.afterAuthentication(this.resolveIdentity(typed,
                "This account is not linked to any company. Please contact your administrator."));
    }

    @Override
    public AuthenticationResult authenticateByPassword(String identifier, String password) {
        // Same message AND same counting for "no such identifier" and "wrong password". One shared
        // message is not enough: the refusal starts naming the remaining attempts from the seventh
        // failure, so an identifier that never counted down would have confirmed its non-existence
        // just as surely as a distinct message. The unknown branch therefore counts the submitted
        // identifier in the same window and words its refusal from that count.
        //
        // Normalised BEFORE the branch, so both branches see the same string. The unknown counter
        // already hashed a trimmed, lowercased form; the lookup here used the raw one, so " x"
        // always fell into the unknown branch while "x" could resolve — and whether the eighth try
        // continued the countdown or started afresh said whether x existed (see LoginIdentifiers).
        // The lookup itself gets the typed form (trimmed, lowercased, separators kept) so that it
        // can also ask for a pre-fold row's spelling; the counters below key on the canonical one.
        String typed = LoginIdentifiers.typedForm(identifier);
        identifier = LoginIdentifiers.normalize(identifier);
        Optional<UserIdentity> resolved = identityService.findByLoginIdentifier(typed);
        if (resolved.isEmpty()) {
            // Same ORDER as the real branch below: locked is answered first and is not counted.
            // Counting while locked would make the unknown branch's lock end at a different moment
            // from the real one's — the eleventh try tells the two apart.
            if (identityService.isUnknownIdentifierLocked(identifier)) {
                throw new BusinessException(lockedMessage());
            }
            long failures = identityService.recordUnknownIdentifierFailure(identifier);
            throw new BusinessException(wrongPasswordMessage(failures));
        }
        UserIdentity identity = resolved.get();

        // Lock checked BEFORE the password, so "locked" versus "incorrect" cannot confirm a guess.
        // Only the password path is locked — code login stays open, because what is under attack
        // is the password and locking the person out entirely would complete the attack for it.
        if (identityService.isPasswordLocked(identity)) {
            throw new BusinessException(lockedMessage());
        }
        if (!identityService.matchesPassword(identity, password)) {
            // Counted per PERSON, so switching company buys no extra tries.
            long failures = identityService.recordPasswordFailure(identity);
            throw new BusinessException(wrongPasswordMessage(failures));
        }
        identityService.clearPasswordFailures(identity.getId());
        return this.afterAuthentication(identity);
    }

    private static String lockedMessage() {
        return "Too many failed attempts. Password login is locked for " + LOCK_MINUTES
                + " minutes. You can still log in with a verification code.";
    }

    /** The refusal for the {@code failures}-th consecutive wrong password. */
    private static String wrongPasswordMessage(long failures) {
        if (failures >= UserIdentityService.FAILURES_BEFORE_LOCK) {
            // This guess is the one that locked: say so, rather than a plain "incorrect" followed
            // by an unexplained "locked" on the next try.
            return lockedMessage();
        }
        if (failures >= WARN_FROM_FAILURE) {
            return "Incorrect account or password. " + (UserIdentityService.FAILURES_BEFORE_LOCK - failures)
                    + " attempt(s) remaining before password login is locked.";
        }
        return "Incorrect account or password.";
    }

    /**
     * Find the PERSON behind a login identifier.
     *
     * <p>Identifiers are unique on {@code UserIdentity} and seeded when a person is created, so
     * this is the whole of the lookup — there is deliberately no fallback to resolving the ACCOUNT
     * by its work contact. Such a fallback existed while identifiers were being introduced, to heal
     * rows created before the seeding; it also required {@code UserAccount.email} to stay globally
     * unique, which is what kept the email index from being narrowed to {@code (tenantId, email)}.
     * With no data predating the seeding, the fallback protects nobody and costs that narrowing.
     *
     * @param notFoundMessage what the caller may safely tell an anonymous stranger. Both reasons
     *                        ("no such identifier", "identifier is not linked to a person") must
     *                        report the same thing: a distinct message would confirm which
     *                        accounts exist.
     */
    private UserIdentity resolveIdentity(String identifier, String notFoundMessage) {
        return identityService.findByLoginIdentifier(identifier)
                .orElseThrow(() -> new BusinessException(notFoundMessage));
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
        boolean mustSetPassword = StringUtils.isBlank(identity.getPassword());
        List<MembershipOption> options = this.resolveMemberships(profileId);
        List<MembershipOption> enterable = options.stream()
                .filter(MembershipOption::selectable).toList();
        if (options.size() == 1 && enterable.size() == 1) {
            return AuthenticationResult.resolved(profileId,
                    profileService.getUserInfo(enterable.get(0).accountId()), mustSetPassword);
        }
        if (options.isEmpty()) {
            throw noCompanyRefusal(profileId);
        }
        // A choice is pending, so authentication succeeded but no session is issued yet. Mint a
        // single-use token proving THIS person just authenticated; selectTenant reads the person
        // from it, never from a client-supplied id — otherwise the company step would be an
        // unauthenticated "issue me a session for profileId X".
        return AuthenticationResult.choicePending(
                profileId, options, mustSetPassword, issuePreAuthToken(profileId));
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
            throw new BusinessException("Your sign-in step expired. Please log in again.");
        }
        return Long.valueOf(value);
    }


    @Override
    public AuthenticationResult confirmJoin(String rawToken, Long profileId, String proof) {
        // Read BEFORE the membership activates: accepting spends the invitation, and neither the
        // row's prior binding nor the invitation's contacts can be resolved from the token after.
        // An unusable token resolves to nothing here and is then judged by the invitation service
        // itself (a refusal, or the quiet re-entrant return), exactly as before.
        boolean boundBefore = invitationService.resolveJoinAccount(rawToken)
                .map(account -> account.getProfileId() != null).orElse(false);
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
        return this.afterAuthentication(identity);
    }

    @Override
    public void sendJoinCode(String rawToken, String channel) {
        // The address never crosses the wire in either direction: the caller sends a token, the
        // invitation service resolves it, and the code goes out to what IT stored.
        String address = invitationService.resolveJoinChannel(rawToken, channel);
        if ("mobile".equalsIgnoreCase(channel)) {
            this.sendMobileCode(address);
        } else {
            this.sendEmailCode(address);
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
        // Code first. Looking the person up before verifying would let a caller probe which
        // identifiers exist by watching which ones fail differently.
        String typed = LoginIdentifiers.typedForm(identifier);
        identifier = LoginIdentifiers.normalize(identifier);
        this.verifyCode(identifier, code);
        assertContactNotShared(typed);
        UserIdentity identity = identityService.findByLoginIdentifier(typed).orElseThrow(
                () -> new BusinessException("Incorrect account or code."));
        // Strength rules and the lock reset both live inside setPassword — a reset must clear the
        // lock, or someone who forgot their password stays locked out of the password they just set.
        identityService.setPassword(identity.getId(), newPassword);
    }

    @Override
    public boolean mustSetPassword(Long profileId) {
        return identityService.findByProfile(profileId)
                .map(identity -> StringUtils.isBlank(identity.getPassword()))
                // Unknown person → do not claim they are fine; the caller fails elsewhere.
                .orElse(Boolean.FALSE);
    }
    @Override
    public List<MembershipOption> listTenants(String authToken) {
        // Reads the person from the token, not the request: listing another person's tenants is
        // a smaller leak than taking over their session, but it is the same unauthenticated call.
        return resolveMemberships(resolvePreAuthToken(authToken));
    }

    private List<MembershipOption> resolveMemberships(Long profileId) {
        // The lock lives on the person's credential, so it is read once and stamped on every
        // option rather than looked up per company.
        boolean locked = identityService.findByProfile(profileId)
                .map(identityService::isPasswordLocked).orElse(false);
        return accountService.listMembershipsOf(profileId).stream()
                // Two kinds of membership, two rules. An EMPLOYMENT that cannot be entered is still
                // shown greyed — it is a standing relationship the person can ask about. A lapsed
                // CONSULTANCY is simply absent: it is not access on hold, it is access they no
                // longer have, and listing it would invite them to ask a tenant that never granted
                // it. canEnter answers the whole consultant question at once (is a consultant at
                // all / not disabled / grant covers today), so nothing here re-derives a third of it.
                .filter(account -> Boolean.TRUE.equals(account.getConsultant())
                        ? consultantService.canEnter(profileId, account.getTenantId())
                        : COUNTED_STATUSES.contains(account.getStatus()))
                .map(account -> new MembershipOption(
                        account.getId(), account.getTenantId(),
                        tenantInfoService == null ? null
                                : tenantInfoService.getTenantName(account.getTenantId()),
                        account.getStatus(), locked, Boolean.TRUE.equals(account.getConsultant())))
                // Selectable first: the common case is one usable company among some frozen ones,
                // and making the person hunt for it in a mixed list is a needless step.
                .sorted(Comparator.comparing(MembershipOption::selectable).reversed())
                .toList();
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
        boolean invited = accountService.listMembershipsOf(profileId).stream()
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
            throw new BusinessException(accountDeniedMessage(chosen.status()));
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
            throw new BusinessException(accountDeniedMessage(chosen.status()));
        }
        // No session minted here — the controller does that, and only after generateSessionId has
        // run the tenant and account gates on the TARGET membership.
        return AuthenticationResult.resolved(
                profileId, profileService.getUserInfo(accountId), this.mustSetPassword(profileId));
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
     * Generate a new session ID for a user
     *
     * @param userId User ID
     * @return Session ID
     */
    @Override
    public String generateSessionId(Long userId) {
        // Tenant + account lifecycle gates — the single choke point every login flow
        // passes through, and it runs AFTER credentials are verified.
        validateTenantActive(userId);
        validateAccountActive(userId);
        String sessionId = UUIDUtils.shortUUID22();
        // Store session ID -> user ID mapping in cache
        String sessionKey = RedisConstant.SESSION + sessionId;
        cacheService.save(sessionKey, userId, RedisConstant.ONE_MONTH);
        return sessionId;
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