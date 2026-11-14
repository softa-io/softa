package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.security.HashUtils;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.RandomUtils;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.user.constant.AccountErrorCodes;
import io.softa.starter.user.dto.InvitationInfo;
import io.softa.starter.user.dto.JoinContacts;
import io.softa.starter.user.dto.JoinEntry;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserInvitation;
import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.enums.*;
import io.softa.starter.user.service.*;
import io.softa.starter.user.spi.PushDeviceRegistry;
import io.softa.starter.user.util.LoginIdentifiers;

/**
 * {@link UserInvitationService} — token issuance + acceptance. Runs under {@link SkipPermissionCheck}
 * (called from tenant/system contexts and from the public set-password endpoint). The raw token is
 * only ever emailed; the DB stores its SHA-256 hash. See {@link UserInvitation} for the model notes.
 */
@Slf4j
@Service
public class UserInvitationServiceImpl extends EntityServiceImpl<UserInvitation, Long>
        implements UserInvitationService {

    /** Invitation / reset links are valid for 7 days. */
    private static final int EXPIRY_DAYS = 7;
    /** Token entropy: 32 random bytes → URL-safe Base64 (~43 chars). */
    private static final int TOKEN_BYTES = 32;
    /** Cap on the unbind reason. Long enough for a sentence, short enough to stay readable. */
    private static final int MAX_REASON_LENGTH = 500;
    /** The invitation's template code, in both channels — same message, different transport: a tenant
     *  template ({@code MailTemplate}, each tenant's own copy, the platform tenant's included) and the
     *  platform's one {@code SmsTemplate}. Both are seeded by the host app. */
    private static final String TEMPLATE_INVITE = "user.invitation";
    /** Mail only, and a platform template ({@code PlatformMailTemplate}). A password reset starts from the
     *  login screen by typing an email, so the address is known and an SMS counterpart would add cost
     *  without reach. */
    private static final String TEMPLATE_RESET = "user.password-reset";

    private final UserAccountService accountService;
    private final UserIdentityService identityService;
    private final MessageService messageService;
    /** Optional: the join screens show the inviting company's name; absent tenant-starter → null. */
    private final TenantInfoService tenantInfoService;
    /** The "code was passed" proof that confirmJoin demands and spends. */
    private final JoinProofGuard proofGuard;
    /** Send limits for one-time codes. A reset link spends the same budget — see forgotPassword. */
    private final VerificationCodeGuard codeGuard;
    /** Site origin every invitation / reset link is built on ({@code system.site-url}). */
    private final SystemConfig systemConfig;

    /** Splits the invitation mobile into dial code, country and masked number for the join page. */
    @Autowired
    private JoinMobileFormat mobileFormat;

    @Autowired
    private UserSecurityLogService securityLog;

    @Autowired
    private UserSessionService sessionService;

    /** Optional: present only where APP push is deployed. */
    @Autowired(required = false)
    private PushDeviceRegistry pushDeviceRegistry;

    public UserInvitationServiceImpl(UserAccountService accountService,
                                     UserIdentityService identityService,
                                     MessageService messageService,
                                     @Autowired(required = false) TenantInfoService tenantInfoService,
                                     JoinProofGuard proofGuard,
                                     VerificationCodeGuard codeGuard,
                                     SystemConfig systemConfig) {
        this.accountService = accountService;
        this.identityService = identityService;
        this.messageService = messageService;
        this.tenantInfoService = tenantInfoService;
        this.proofGuard = proofGuard;
        this.codeGuard = codeGuard;
        this.systemConfig = systemConfig;
    }

    /**
     * Advance the account's status for an outgoing invitation, returning whether it changed
     * (so the caller only writes when there is something to write).
     *
     * <p>Create→invite is decoupled: creating a user contacts nobody — this explicit Invite
     * does, and it is what advances {@code PENDING → INVITED}. {@code confirmJoin} then flips
     * {@code INVITED → ACTIVE}, giving the three-state axis the account list renders.
     *
     * <p>The gate is "has no password yet", not "is PENDING", for two reasons:
     * an account already sitting on {@code INVITED} must stay invitable (re-sending the link
     * is the normal remedy for a lost or expired mail) yet needs no write; and one that
     * already has a password ({@code ACTIVE} / {@code LOCKED} / …) is left untouched —
     * re-inviting must never demote a working account.
     *
     * <p>{@code hasPassword} arrives as a parameter rather than being read off the account:
     * credentials live on {@link io.softa.starter.user.entity.UserIdentity}, resolved through
     * the person, and this method stays static so the axis is testable without wiring beans.
     */
    static boolean applyInviteTransition(UserAccount account, boolean hasPassword) {
        if (hasPassword || account.getStatus() == AccountStatus.INVITED) {
            return false;
        }
        account.setStatus(AccountStatus.INVITED);
        return true;
    }

    @SkipPermissionCheck
    @Override
    @Transactional
    public void invite(Long userId, Long invitedBy) {
        Assert.notNull(userId, "userId is required");
        UserAccount account = accountService.getById(userId)
                .orElseThrow(() -> new BusinessException("User not found."));
        // At least ONE channel must be maintained — the invitation goes to every channel the
        // account has (work mobile SMS + work email), so requiring the email specifically would
        // block an account reachable only by phone. Refusing here (rather than issuing a token
        // nobody receives) is what keeps the account's status honest: it stays PENDING, so the
        // list still reads "not contacted" instead of claiming an invitation went out.
        if (StringUtils.isBlank(account.getEmail()) && StringUtils.isBlank(account.getMobile())) {
            throw new BusinessException(
                    "This user has no work email or work mobile — add a contact method before inviting.");
        }
        // Create→invite is decoupled: creating a user contacts nobody — this explicit Invite does,
        // and it is what advances PENDING → INVITED. "Has this person set a password yet?" — read
        // from the person's credentials when the account is linked. An account with no profile link
        // is broken legacy/partial data (it already cannot log in); treat it as password-less so an
        // invite still marks it INVITED and sends the set-password link, rather than throwing
        // requireIdentity's "not linked to a person" and dead-ending the one operation that could
        // recover it.
        boolean hasPassword = account.getProfileId() != null
                && StringUtils.isNotBlank(identityService.requireIdentity(account).getPassword());
        if (applyInviteTransition(account, hasPassword)) {
            accountService.updateOne(account);
        }
        issue(account, InvitationPurpose.INVITE, invitedBy);
    }

    /** Statuses a mis-binding can be corrected from with Unbind & Re-invite. */
    private static final Set<AccountStatus> UNBINDABLE = Set.of(
            AccountStatus.INVITED, AccountStatus.ACTIVE, AccountStatus.LOCKED, AccountStatus.FROZEN);

    @SkipPermissionCheck
    @CrossTenant
    @Override
    @Transactional
    public void unbindAndReinvite(Long userId, String reason, Long operatedBy) {
        Assert.notNull(userId, "userId is required");
        Assert.notBlank(reason, "A reason is required to unbind this account.");
        Assert.isTrue(reason.trim().length() <= MAX_REASON_LENGTH,
                "The reason cannot exceed " + MAX_REASON_LENGTH + " characters.");
        // From the employee record, not the caller: the corrected contact is HR's edit
        // to the record, and this operation carries it onto the membership. A typo is fixed where
        // it lives rather than retyped here, so the two cannot end up disagreeing.
        // The enterprise email is the record's Work Email; the invitation goes to the record's
        // personal mobile, which becomes the login mobile of whoever joins.
        String email = StringUtils.trimToNull(accountService.archiveWorkContacts(userId).email());
        String mobile = LoginIdentifiers.collapseMobile(StringUtils.trimToNull(
                accountService.archivePersonalMobile(userId)));
        if (mobile == null) {
            throw new BusinessException(
                    "Enter the employee's personal mobile on their record before re-inviting.");
        }

        UserAccount account = accountService.getById(userId)
                .orElseThrow(() -> new BusinessException("User not found."));
        // PENDING is excluded on purpose: nobody has accepted, so there is nothing to unbind —
        // correct the contact and Send. DEACTIVATED is excluded because the membership is closed;
        // bringing it back is reviveMembership, not a re-invitation.
        if (!UNBINDABLE.contains(account.getStatus())) {
            throw new BusinessException("This account cannot be unbound in its current state.");
        }
        // The record has to have changed: re-inviting to the very contacts the last invitation went
        // to reaches the same wrong person again. Asked before anyone is signed out or detached.
        this.refuseUnchangedInvitation(userId, email, mobile);
        // Refused here rather than at the unique index: "this email is already registered" from a
        // constraint violation names no account and arrives after the unbind has been written.
        accountService.findContactHolderInTenant(email, userId)
                .ifPresent(other -> {
                    throw new BusinessException(
                            "That work email already belongs to another account.");
                });

        // ① The wrong person is signed out of this company on every device, FIRST: a failure after
        // this point leaves a detached membership, which is recoverable. Their login mobile is
        // their own and stays — it signs them into their other companies — and the enterprise
        // email stops signing them in here as soon as the account is no longer joined.
        sessionService.revokeAccount(userId, SessionRevokeReason.UNBOUND);

        // ② Detach, ③ record the corrected contacts, and reset to "not contacted yet".
        Long previousProfileId = account.getProfileId();
        account.setProfileId(null);
        account.setActivationTime(null);
        account.setEmail(email);
        account.setMobile(mobile);
        account.setStatus(AccountStatus.INVITED);
        // updateOne(entity, false): detaching means WRITING nulls, which the default overload
        // drops — it would leave the membership attached to the wrong person and report success.
        accountService.updateOne(account, false);

        // ④ Fresh token, every outstanding one revoked inside issue().
        issue(account, InvitationPurpose.REINVITE, operatedBy, reason.trim());
        UserSecurityLog row = securityLog.entry(SecurityAction.UNBIND_AND_REINVITE,
                SecurityLogSource.USER_ACCOUNTS, account, previousProfileId);
        row.setReason(reason.trim());
        securityLog.record(row);
        log.warn("Account {} unbound from profile {} and re-invited by {}. Reason: {}",
                userId, previousProfileId, operatedBy, reason.trim());
    }

    /**
     * Refuse when the record's personal mobile and work email both equal what this account's last
     * invitation went to. Mobiles are compared in their normalised form, a blank email equals a blank
     * one. An account never invited has nothing to compare and is let through. A forgot-password
     * link is not an invitation and does not count.
     */
    private void refuseUnchangedInvitation(Long accountId, String email, String mobile) {
        Optional<UserInvitation> last = this.searchList(new Filters().eq(UserInvitation::getUserId, accountId)).stream()
                .filter(invitation -> invitation.getPurpose() != InvitationPurpose.PASSWORD_RESET)
                .max(java.util.Comparator.comparing(UserInvitation::getCreatedTime,
                                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                        .thenComparing(UserInvitation::getId, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
        if (last.isEmpty()) {
            return;
        }
        boolean sameMobile = Objects.equals(LoginIdentifiers.normalize(StringUtils.trimToNull(mobile)),
                LoginIdentifiers.normalize(StringUtils.trimToNull(last.get().getMobile())));
        boolean sameEmail = StringUtils.equalsIgnoreCase(StringUtils.trimToNull(email),
                StringUtils.trimToNull(last.get().getEmail()));
        if (sameMobile && sameEmail) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.INVITATION_UNCHANGED,
                    "The personal mobile and work email are the same as the last invitation. "
                            + "Update the employee profile first.");
        }
    }

    @SkipPermissionCheck
    @Override
    @Transactional
    public void revokeInvitation(Long userId) {
        Assert.notNull(userId, "userId is required");
        UserAccount account = accountService.getById(userId)
                .orElseThrow(() -> new BusinessException("User not found."));
        // Credentials live on the person; an account with no profile link cannot have joined, so
        // treat it as password-less — revoke stays applicable rather than throwing on broken data.
        boolean hasPassword = account.getProfileId() != null
                && StringUtils.isNotBlank(identityService.requireIdentity(account).getPassword());
        boolean statusChanged = applyRevokeTransition(account, hasPassword);
        // Kill the live link regardless of the status write: revoking an account already sitting
        // on PENDING (a stray token from an earlier cycle) still has to invalidate that token —
        // gating the revoke on the status change would leave it working.
        revokePending(userId);
        if (statusChanged) {
            accountService.updateOne(account);
        }
        log.info("Invitation revoked for user {} — link invalidated, account back to PENDING.", userId);
    }

    @SkipPermissionCheck
    @Override
    @Transactional
    public void forgotPassword(String email) {
        if (StringUtils.isBlank(email)) {
            return;
        }
        // Asked BEFORE anything is looked up, so its refusal is the same answer for an address that
        // exists and one that does not: it is keyed by what the caller typed, never by what was found.
        //
        // This was the one unauthenticated identifier entry point with no send limit. The code paths
        // acquired one; this one issues a link instead and was not revisited. Unmetered it mails a
        // real person a real reset link as fast as it is asked to, and it caps nothing for an
        // attacker timing the two branches — issuing mints a token and writes a row, finding nothing
        // returns after a single query, and the difference is only worth measuring if you may keep
        // asking.
        //
        // Deliberately the SAME counter the login codes use rather than a prefix of its own: ten
        // sends a day to one address is the budget for reaching that person at all, however the
        // reaching is dressed. The two therefore compete — a day spent on login codes leaves none for
        // a reset, and either route puts the address into the other's 60-second cooldown. For someone
        // who genuinely cannot get in, ten attempts across both routes is already past the point
        // where the answer is their HR, which is what the screen tells them.
        codeGuard.beforeSend(LoginIdentifiers.normalize(email));
        Optional<ResetTarget> target = resolveResetTarget(email);
        if (target.isEmpty()) {
            // One log line and one (empty) response for "unknown", "no password yet", "no active
            // membership" and "not the person's own login email" alike — telling them apart would
            // make this an oracle over who is registered where.
            log.info("forgotPassword for an identifier with nothing to reset — ignored (no enumeration).");
            return;
        }
        issue(target.get().row(), InvitationPurpose.PASSWORD_RESET, null, null, target.get().address());
    }

    /**
     * Where a self-service reset goes and which row records it. {@code address} is the person's own
     * login email in canonical form; {@code row} is one of their ACTIVE memberships and exists only
     * so the invitation has a userId to be filed under — it decides nothing about delivery.
     */
    private record ResetTarget(UserAccount row, String address) {
    }

    /**
     * The membership a self-service reset may be issued against, or empty when nothing may be.
     *
     * <p>Resolved as the PERSON, by login identifier — the same lookup and spelling login uses —
     * never as "the account whose work email this is". The password is global: whoever receives
     * the link sets the credential that opens every company the identity belongs to. A work
     * mailbox is the company's, not the person's — it is reissued, and a re-hired leaver's revived
     * row carries it while still PENDING, with the person's own login elsewhere. Matching that row
     * by its work column would mail the company a link that sets the leaver's password. Two things
     * therefore have to hold before a token exists at all, and both are proved again at
     * {@link #acceptToken} because the link outlives this call:
     * <ol>
     * <li>the identifier is a LOGIN identifier of an identity that already has a password — a reset
     *     replaces a credential; a first password is set in-session or through /join with a code,
     *     and neither of those paths is a mailbox alone;</li>
     * <li>the person holds at least one ACTIVE membership, and the token names one of those. The
     *     row an invitation points at is what acceptToken reads back, so it must be one that has
     *     already been confirmed — never a PENDING / INVITED row that a confirm step still guards,
     *     nor a DEACTIVATED one;</li>
     * <li>the link is delivered to the login email that resolved the person, and nowhere else. The
     *     address the link goes to IS the proof of who redeems it: a work mailbox proves the
     *     company that holds it, the login identifier proves the person. Resolving the person
     *     correctly and then mailing an ACTIVE row's work address would hand the company a link
     *     that replaces the person's global password — it only has to type the personal login
     *     it already has on file. So the ACTIVE row is bookkeeping only (the invitation needs a
     *     userId), and the mailbox it carries is never consulted.</li>
     * </ol>
     * The row recorded is the ACTIVE one whose work email is the identifier when there is one —
     * the token is then filed under the company the person addressed — else any ACTIVE row; which
     * one no longer changes where the mail lands. A mobile login identifier resolves the person but
     * has no mailbox to prove, so it gets nothing.
     */
    private Optional<ResetTarget> resolveResetTarget(String identifier) {
        // A reset link is mailed: only an email can carry it. A mobile resolves the person too, but
        // there is no mailbox in it to prove.
        if (identifier == null || !identifier.contains("@")) {
            return Optional.empty();
        }
        String typed = LoginIdentifiers.typedForm(identifier);
        Optional<UserIdentity> identity = identityService.findByLoginIdentifier(typed);
        if (identity.isEmpty() || StringUtils.isBlank(identity.get().getPassword())) {
            return Optional.empty();
        }
        // The lookup itself proved the address signs this person in — the enterprise email of an
        // account they have joined, or a consultant's own address — so it is theirs to reset by.
        String canonical = LoginIdentifiers.normalize(identifier);
        List<UserAccount> active = accountService.listMembershipsOf(identity.get().getProfileId()).stream()
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
                .toList();
        return active.stream()
                .filter(account -> canonical.equals(LoginIdentifiers.normalize(account.getEmail())))
                .findFirst()
                .or(() -> active.stream().findFirst())
                .map(row -> new ResetTarget(row, canonical));
    }

    /**
     * Move the account back for a withdrawn invitation, returning whether it changed.
     *
     * <p>Refuses outright once the account has a password: the person has already joined, and
     * "revoking" them would strand a working account in a pre-activation state nothing can move
     * forward. Ending an existing membership is off-boarding — a different action with different
     * obligations (release the work-email binding).
     *
     * <p>{@code hasPassword} is passed in rather than read off the account: credentials live on the
     * person's {@link io.softa.starter.user.entity.UserIdentity} now, and keeping this static leaves
     * the axis unit-testable without wiring beans.
     *
     * @throws BusinessException when the account has already been activated
     */
    static boolean applyRevokeTransition(UserAccount account, boolean hasPassword) {
        if (hasPassword) {
            throw new BusinessException("This user has already joined — revoke does not apply.");
        }
        if (account.getStatus() != AccountStatus.INVITED) {
            return false;
        }
        account.setStatus(AccountStatus.PENDING);
        return true;
    }

    /**     * Revoke prior PENDING tokens for the user, issue a fresh one, and email the link.
     *
     * <p>{@link UserInvitation} is multiTenant, so the ORM auto-stamps {@code tenant_id} from the CURRENT
     * request context — with {@code enableMultiTenancy=true}, {@code tenant_id} is readonly and CANNOT be
     * set explicitly. {@link #invite} runs under the target tenant's context (the provisioning
     * orchestrator's {@code inTenantContext(newTenantId)}), so the stamp is correct there.
     *
     * <p><b>Do NOT source the tenant from {@code account.getTenantId()}</b>: UserAccount is non-multiTenant,
     * so its own {@code tenant_id} is readonly-dropped on insert (always null) — reading it and pinning the
     * context to it would stamp the invitation null. The public {@link #forgotPassword} path has no tenant
     * context, so its reset rows carry a null {@code tenant_id} (they are looked up cross-tenant by token,
     * so a null only affects the authed 明细 scoping — a super-admin still sees them; a tenant-admin does not).
     */
    private void issue(UserAccount account, InvitationPurpose purpose, Long invitedBy) {
        this.issue(account, purpose, invitedBy, null);
    }

    private void issue(UserAccount account, InvitationPurpose purpose, Long invitedBy, String reason) {
        this.issue(account, purpose, invitedBy, reason, null);
    }

    /**
     * @param deliverTo when non-null, the ONE address the link is sent to and recorded under, in
     *                  place of the account's work email / mobile fan-out. A self-service reset
     *                  passes the login identifier that resolved the person: the address is what
     *                  proves who redeems the link, and the account's work contacts prove the
     *                  company, not the person.
     */
    private void issue(UserAccount account, InvitationPurpose purpose, Long invitedBy, String reason,
                       String deliverTo) {
        revokePending(account.getId());

        // Who this invitation BELONGS to: the invitee's own tenant, never the caller's. It is a record
        // of that person's account at that company, so it is that company's row — following whoever
        // happened to click files it under a different company entirely whenever an operator can see
        // across tenants. Provisioning agrees anyway: it creates the account inside
        // inTenantContext(newTenantId) before inviting. Ambient is only the fallback for an account
        // carrying no tenant of its own.
        //
        // Deliberately NOT the same value as the render tier chosen further down. Ownership and tier
        // answer different questions, and on the public forgotPassword path they legitimately part
        // ways: the row still belongs to the account's tenant, while the mail renders platform-tier
        // because there is no context to trust and no cross-tier template fallback to catch a miss.
        Long owningTenant = account.getTenantId() != null
                ? account.getTenantId()
                : ContextHolder.getContext().getTenantId();

        String rawToken = RandomUtils.randomString(TOKEN_BYTES);
        UserInvitation invitation = new UserInvitation();
        invitation.setUserId(account.getId());
        // Both identifiers are recorded, so the row answers "which channels was this addressed
        // to?" — which the email alone could not once delivery fanned out. The delivery OUTCOME
        // (sent / bounced / retried) stays in message-starter's send records; duplicating it here
        // would give two answers that drift. With an explicit address, that address is the only
        // channel — and acceptToken reads it back as the proof the link was mailed to the person.
        boolean pinned = deliverTo != null;
        invitation.setEmail(pinned ? deliverTo : account.getEmail());
        invitation.setMobile(pinned ? null : account.getMobile());
        invitation.setPurpose(purpose);
        invitation.setTokenHash(HashUtils.sha256Hex(rawToken));
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setInvitedBy(invitedBy);
        invitation.setReason(reason);
        invitation.setExpiresAt(LocalDateTime.now().plusDays(EXPIRY_DAYS));
        // "sent" here = requested; the actual delivery + status is message-starter's (MailSendRecord).
        invitation.setSentAt(LocalDateTime.now());
        // Pinned rather than left to the ambient stamp. UserInvitation is multiTenant, and the ORM skips
        // tenant stamping entirely while crossTenant is set — which UserAccountController.invite sets for
        // a platform super-admin so it can reach a roster spanning tenants. The read needed that window;
        // the write inherited it, and every invitation an operator sent landed with a null tenant_id.
        // Those rows are invisible to the tenant whose member they are about: their User Invitations page
        // filters on tenant_id, and null matches nothing — so the one case where someone acts on a
        // tenant's behalf is the one case that left them no record of it.
        //
        // inTenantContext clears crossTenant and pins the id; createdId / createdBy survive because they
        // come from Context.userId / Context.name, which it carries over.
        ContextUtils.inTenantContext(owningTenant, () -> this.createOne(invitation));

        // Send the set-password / reset email. The send record joins this transaction, so the mail
        // only goes out once this invitation has committed.
        // The two purposes land on DIFFERENT pages, and must. A password reset is exactly one
        // action — set a credential — while an invitation has to check the link, verify identity,
        // set a password if the person is new, and then confirm joining, because that confirmation
        // is what binds them to the company. Sending an invitation to /set-password (as this did)
        // sets a password and leaves the membership INVITED forever: the person appears to have
        // completed the flow and still cannot get in.
        String page = purpose == InvitationPurpose.PASSWORD_RESET ? "/set-password" : "/join";
        String link = systemConfig.resolveSiteUrl() + page + "?token=" + rawToken;
        // Deliver to EVERY channel the account has, not just the email: an employee reachable
        // only by work mobile is a normal case, and one who has both should not depend on which
        // inbox they check first. Both carry the SAME link — it is one invitation, so accepting
        // from either channel consumes the same token.
        //
        // Tier of the render: an invitation in a tenant context is the tenant's mail — its own
        // template and wording, its own server or the platform's relay. A password reset is the
        // platform's whatever the context (an account credential: the platform's template through
        // the platform's servers), and so is anything sent with no tenant context, such as the public
        // forgotPassword path.
        //
        // WHICH tenant, though, is the account's — not the ambient one. They differ exactly when an
        // operator who can see across tenants clicks Invite: the ambient context is then the
        // OPERATOR's company, and sourcing the tier from it renders another company's template and
        // routes through another company's mail server for a mail about this person's account at
        // theirs. On every other path the two already agree, since provisioning creates the account
        // inside inTenantContext(newTenantId) before inviting.
        //
        // The no-context path stays the platform's rather than reaching for the account's tenant:
        // it is the public forgotPassword path, a reset, and the reset template is the platform's.
        Long tenantId = ContextHolder.getContext().getTenantId() != null ? account.getTenantId() : null;
        MessageScope scope = tenantId != null && purpose != InvitationPurpose.PASSWORD_RESET
                ? MessageScope.TENANT : MessageScope.PLATFORM;
        // Whom the send is filed under and counted against — a different question from the tier. In
        // a tenant context it is the account's company. The public reset has no context, and a reset
        // sets the person's one password rather than one account's: it is a company's when the person
        // has joined only that one, and the platform's when they have joined several.
        Long sendFor = tenantId != null || purpose != InvitationPurpose.PASSWORD_RESET ? tenantId
                : accountService.soleJoinedTenantOf(account.getProfileId()).orElse(null);
        Map<String, Object> variables = Map.of("link", link, "expiryDays", EXPIRY_DAYS);
        String mailTo = pinned ? deliverTo : account.getEmail();
        if (StringUtils.isNotBlank(mailTo)) {
            String template = purpose == InvitationPurpose.PASSWORD_RESET ? TEMPLATE_RESET : TEMPLATE_INVITE;
            sendIn(sendFor, () -> messageService.sendMail(
                    SendMailDTO.ofTemplate(List.of(mailTo), template, variables, scope)));
        }
        // Invitations only. A password reset is started from the login screen by typing an email,
        // so the address is known and SMS adds nothing but cost. Same tier as the mail above.
        if (!pinned && purpose != InvitationPurpose.PASSWORD_RESET && StringUtils.isNotBlank(account.getMobile())) {
            sendIn(sendFor, () -> messageService.sendSms(
                    SendSmsDTO.ofTemplate(account.getMobile(), TEMPLATE_INVITE, variables, scope)));
        }
        // Dev aid: surface the set-password / reset link so it can be copied from the logs when SMTP
        // is not wired locally. ⚠️ The link carries a one-time credential token — lower this to debug or
        // remove it before production so the token is not leaked into prod logs.
        log.debug("Invitation link ({}) for {}: {}", purpose, mailTo, link);
    }

    /**
     * Send in the tenant named — the send is filed under it and counted against its quota — or, with
     * none, in the caller's own context: the public reset of someone in several companies, filed under
     * the platform. A send message-starter rejects (an address on file that does not parse) is logged
     * at ERROR: the invitation stands either way.
     */
    private static void sendIn(Long tenantId, Runnable send) {
        try {
            if (tenantId == null) {
                send.run();
            } else {
                ContextUtils.inTenantContext(tenantId, send);
            }
        } catch (BusinessException e) {
            log.error("Invitation message not sent: {}", e.getMessage());
        }
    }

    @Override
    @SkipPermissionCheck
    @CrossTenant
    public void revokeOutstandingLinks(Long userId) {
        revokePending(userId);
    }

    private void revokePending(Long userId) {
        // A user has at most a handful of invitations; filter PENDING in memory to avoid an
        // enum-valued query filter.
        List<UserInvitation> existing = this.searchList(new Filters().eq(UserInvitation::getUserId, userId));
        for (UserInvitation invitation : existing) {
            if (invitation.getStatus() == InvitationStatus.PENDING) {
                invitation.setStatus(InvitationStatus.REVOKED);
                this.updateOne(invitation);
            }
        }
    }

    // @CrossTenant: public set-password endpoint has NO tenant context; look the (multiTenant) row up
    // by tokenHash across all tenants. The token hash is the global unique key.
    @SkipPermissionCheck
    @CrossTenant
    @Override
    @Transactional
    public void acceptToken(String rawToken, String newPassword) {
        // Reached only by the emailed-link reset (/login/resetPassword). Invitations do not land
        // here — they point at /join, whose verify → set-password → confirm is what activates a
        // membership. So this endpoint changes the PASSWORD only and leaves the account's status
        // alone: a reset token's account already has a password, and activating anything from a
        // reset link would make "I forgot my password" a way to skip the confirm step.
        Assert.notBlank(rawToken, "This link is invalid.");
        Assert.notBlank(newPassword, "New password cannot be empty.");
        // Strength is checked inside credentialService.setPassword against the person's own
        // identifiers — a bare minimum-length assertion here would contradict it.

        UserInvitation invitation = this.searchOne(
                        new Filters().eq(UserInvitation::getTokenHash, HashUtils.sha256Hex(rawToken)))
                .orElseThrow(() -> new BusinessException("This link is invalid."));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException("This link has already been used or is no longer valid.");
        }
        if (invitation.getExpiresAt() != null && invitation.getExpiresAt().isBefore(LocalDateTime.now())) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            this.updateOne(invitation);
            throw new BusinessException("This link has expired. Please request a new one.");
        }

        UserAccount account = accountService.getById(invitation.getUserId())
                .orElseThrow(() -> new BusinessException("Account not found."));
        // forgotPassword proved three things when it issued this: the row is an ACTIVE membership,
        // its person already has a password, and the link was mailed to that person's own login
        // email. All three are proved AGAIN here because the link is valid for days and this
        // endpoint is anonymous — in between, the membership can be closed, or a token from before
        // those checks existed can still be in a mailbox. The address the link went to is the
        // proof of who holds it: a work mailbox proves the company, the login identifier proves
        // the person. A token addressed anywhere but the identity's login email — one issued
        // against a row's work address before delivery was pinned, or by anything else — was
        // received by whoever holds that mailbox, and is refused before it can replace the
        // person's global credential. Only an already-confirmed row whose person already set a
        // credential may have it replaced from a mailbox at all; any other row is a first
        // password, which belongs to /join (with a code) or to a session.
        Optional<UserIdentity> identity = account.getProfileId() == null
                ? Optional.empty()
                : identityService.findByProfile(account.getProfileId());
        if (account.getStatus() != AccountStatus.ACTIVE
                || identity.isEmpty() || StringUtils.isBlank(identity.get().getPassword())
                || !this.addressedToThem(invitation, identity.get())) {
            throw new BusinessException("This link is no longer valid. Please contact your administrator.");
        }
        // The password goes on the PERSON, so accepting an invitation from company B when you
        // already work at company A replaces one global credential rather than minting a second.
        identityService.setPassword(account, newPassword);

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(LocalDateTime.now());
        this.updateOne(invitation);

        // As a reset by code ends: whoever knew the old password may be signed in somewhere, so every
        // session of the person ends (and the sign-in tokens issued before), and no device keeps
        // receiving their notifications.
        Long profileId = identity.get().getProfileId();
        sessionService.revokePerson(profileId, null, SessionRevokeReason.PASSWORD_RESET);
        if (pushDeviceRegistry != null) {
            pushDeviceRegistry.unregisterPerson(profileId);
        }
        securityLog.record(securityLog.entry(SecurityAction.PASSWORD_RESET, SecurityLogSource.WEB, account, profileId));

        log.info("User {} set password via {} token (invitation {}).",
                invitation.getUserId(), invitation.getPurpose(), invitation.getId());
    }

    /**
     * Whether the link went to an address that signs this very person in — the enterprise email of an
     * account they have joined, or a consultant's own address. A blank one, or one that now signs in
     * nobody or someone else, never matches.
     */
    private boolean addressedToThem(UserInvitation invitation, UserIdentity identity) {
        String sentTo = StringUtils.trimToNull(invitation.getEmail());
        if (sentTo == null) {
            return false;
        }
        try {
            return identityService.findByLoginIdentifier(LoginIdentifiers.typedForm(sentTo))
                    .map(holder -> Objects.equals(holder.getProfileId(), identity.getProfileId()))
                    .orElse(false);
        } catch (BusinessException shared) {
            return false;   // the address names more than one person: not proof of this one
        }
    }

    /**
     * Confirm joining: bind the person to the membership and activate it.
     *
     * <p><b>Binding happens HERE, not when the password was set.</b> Someone who sets a password
     * and closes the tab has proved control of the invitation but has not agreed to join this
     * company — their membership stays INVITED and the confirm screen is what they return to.
     * Activating early would make "set a password" mean "accepted", which is exactly the
     * mis-binding the confirm screen exists to prevent.
     *
     * <p>Re-entrant on purpose: a double-tapped Join Now must not fail. An already-activated
     * membership returns quietly rather than throwing, because from the person's point of view
     * they did join.
     *
     * @param rawToken  the invitation token, re-validated here — the caller may have held the
     *                  page open long enough for a revoke or re-send to land
     * @param profileId the person who verified their identity in the preceding step
     * @param proof     what verifyJoinCode handed back for that step; spent here on success
     */
    @SkipPermissionCheck
    @CrossTenant
    @Override
    @Transactional
    public void confirmJoin(String rawToken, Long profileId, String proof) {
        Assert.notNull(profileId, "profileId is required");
        JoinEntry entry = this.inspectJoinToken(rawToken);
        if (!entry.usable()) {
            // Re-entrant: a double tap is not an error. The first confirm marked the invitation
            // accepted, which the entry check reads as a spent link, so a tap that follows it lands
            // here too and is told nothing it does not already know.
            if (entry.reason() == JoinEntry.Reason.ALREADY_JOINED || this.acceptedBy(rawToken, profileId)) {
                return;
            }
            throw new BusinessException("This link is no longer valid. Please contact your administrator.");
        }
        // After the quiet ALREADY_JOINED return, deliberately: the successful confirm just spent the
        // proof, so a double tap arrives without one and would otherwise be told to verify a code
        // for a membership it already activated. Before everything else, though: this endpoint is
        // anonymous and re-accepts the profileId, and for a bound row that id is the only tie —
        // whoever holds the link (a company holding the work mailbox it landed in) could name the
        // roster's id for that person and bind without ever passing the code. See JoinProofGuard.
        proofGuard.require(proof, rawToken, profileId);
        UserInvitation invitation = this.searchOne(new Filters()
                        .eq(UserInvitation::getTokenHash, HashUtils.sha256Hex(rawToken)))
                .orElseThrow(() -> new BusinessException("This link is invalid."));
        UserAccount account = accountService.getById(invitation.getUserId())
                .orElseThrow(() -> new BusinessException("Account not found."));

        // A row bound to someone else is not reassigned by whoever holds the link — unless that
        // someone is only the placeholder minted when HR created the account (no password, nothing
        // else joined): the person is decided at /join by the number verified, and verifyJoinCode
        // has just decided it.
        if (account.getProfileId() != null && !account.getProfileId().equals(profileId)
                && !this.isPlaceholderPerson(account.getProfileId(), account.getId())) {
            throw new BusinessException("This link does not belong to that account.");
        }

        // The person already holds a live account in this company.
        Optional<UserAccount> here = accountService.findMembershipInTenant(account.getTenantId(), profileId)
                .filter(existing -> !existing.getId().equals(account.getId()));
        if (here.isPresent()) {
            this.logJoinBlocked(account, profileId, here.get().getStatus() == AccountStatus.DEACTIVATED
                    ? "Previously left this company" : AccountErrorCodes.ALREADY_JOINED);
            if (here.get().getStatus() == AccountStatus.DEACTIVATED) {
                throw new BusinessException("This person previously left this company. Use "
                        + "the re-hire flow rather than a new invitation.");
            }
            throw AccountErrorCodes.refusal(AccountErrorCodes.ALREADY_JOINED,
                    "You have already joined this company. Please sign in.");
        }

        // The enterprise email is one person's across the whole system (Deactivated aside): checked
        // again here because another company may have given it to someone since the record was saved.
        if (accountService.isEmailHeldByAnotherPerson(account.getEmail(), profileId, account.getId())) {
            this.logJoinBlocked(account, profileId, AccountErrorCodes.EMAIL_TAKEN);
            throw AccountErrorCodes.refusal(AccountErrorCodes.EMAIL_TAKEN,
                    "This email is already linked to another account. Please contact your HR.");
        }

        if (!profileId.equals(account.getProfileId())) {
            // Sessions still naming this account were opened for whoever held it before.
            sessionService.revokeAccount(account.getId(), SessionRevokeReason.UNBOUND);
        }
        account.setProfileId(profileId);
        account.setStatus(AccountStatus.ACTIVE);
        // From here the list shows the number the person signs in with, not the invitation's.
        identityService.findByProfile(profileId)
                .map(UserIdentity::getLoginMobile)
                .filter(StringUtils::isNotBlank)
                .ifPresent(account::setMobile);
        account.setActivationTime(LocalDateTime.now());
        accountService.updateOne(account);

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(LocalDateTime.now());
        this.updateOne(invitation);
        // Spent, not left to expire: the flow it authorized is complete.
        proofGuard.consume(proof);
        log.info("Profile {} joined tenant {} via invitation {}.",
                profileId, account.getTenantId(), invitation.getId());
    }

    /**
     * Whether a person is only the placeholder created together with this account: no password, and
     * no account outside Deactivated other than this one. Such a person is nobody yet, so /join may
     * put the verified person in its place.
     */
    @Override
    @SkipPermissionCheck
    @CrossTenant
    public boolean isPlaceholderPerson(Long profileId, Long accountId) {
        boolean holdsOthers = accountService.listMembershipsOf(profileId).stream()
                .anyMatch(other -> !other.getId().equals(accountId));
        boolean hasPassword = identityService.findByProfile(profileId)
                .map(identity -> StringUtils.isNotBlank(identity.getPassword())).orElse(false);
        return !holdsOthers && !hasPassword;
    }

    private void logJoinBlocked(UserAccount account, Long profileId, String why) {
        UserSecurityLog row = securityLog.entry(SecurityAction.JOIN_BLOCKED, SecurityLogSource.WEB, account, profileId);
        row.setReason(why);
        securityLog.record(row);
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public JoinContacts resolveJoinContacts(String rawToken) {
        // Re-runs the full five-check gate rather than trusting the page's earlier call: the
        // invitation may have been revoked or re-sent since the page loaded.
        if (!this.inspectJoinToken(rawToken).usable()) {
            throw new BusinessException("This invitation link is no longer usable.");
        }
        // The ACCOUNT's current contacts, not what the invitation was sent to: HR may have corrected
        // the record since, and the code must go to the number on file now, not to an old one.
        UserAccount account = this.resolveJoinAccount(rawToken)
                .orElseThrow(() -> new BusinessException("This invitation link is no longer usable."));
        return new JoinContacts(account.getEmail(), account.getMobile());
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public Optional<UserAccount> resolveJoinAccount(String rawToken) {
        // The gate runs again for the same reason resolveJoinContacts runs it: the caller is about
        // to act on the invitation, and it may have been revoked or re-sent since the page loaded.
        if (!this.inspectJoinToken(rawToken).usable()) {
            return Optional.empty();
        }
        return this.searchOne(new Filters()
                        .eq(UserInvitation::getTokenHash, HashUtils.sha256Hex(rawToken)))
                .flatMap(invitation -> accountService.getById(invitation.getUserId()));
    }

    /**
     * The /join entry check — five conditions, in this order.
     *
     * <p><b>The order is the requirement, not an implementation detail.</b> A token that was
     * superseded by a re-send is "invalid" even if the account is also already active; reporting
     * the account state first would tell the person to contact HR when the real remedy is "open
     * the newest link". Checking cheapest-and-most-specific first also keeps a stranger holding a
     * leaked token from learning anything about the account behind it.
     *
     * <p>The first four all render as "this link has expired" on screen; the reason travels anyway
     * because it is what support needs to decide between re-send, re-invite, and "you already
     * joined".
     */
    @SkipPermissionCheck
    @CrossTenant
    @Override
    public JoinEntry inspectJoinToken(String rawToken) {
        if (StringUtils.isBlank(rawToken)) {
            return JoinEntry.rejected(JoinEntry.Reason.LINK_INVALID);
        }
        Optional<UserInvitation> found = this.searchOne(new Filters()
                .eq(UserInvitation::getTokenHash, HashUtils.sha256Hex(rawToken)));
        // ① unknown token, or superseded by a re-send / revoke (those set status away from PENDING)
        if (found.isEmpty() || found.get().getStatus() != InvitationStatus.PENDING) {
            return JoinEntry.rejected(JoinEntry.Reason.LINK_INVALID);
        }
        UserInvitation invitation = found.get();
        // Only an invitation is a join link. A forgot-password link names an account its owner has
        // long joined — treated as one, it lands on "already joined" and walks past the proof
        // check that guards binding. It answers like an unknown token.
        if (invitation.getPurpose() == InvitationPurpose.PASSWORD_RESET) {
            return JoinEntry.rejected(JoinEntry.Reason.LINK_INVALID);
        }
        // ② past its 7-day life
        if (invitation.getExpiresAt() != null && invitation.getExpiresAt().isBefore(LocalDateTime.now())) {
            return JoinEntry.rejected(JoinEntry.Reason.LINK_EXPIRED);
        }
        Optional<UserAccount> account = accountService.getById(invitation.getUserId());
        if (account.isEmpty()) {
            return JoinEntry.rejected(JoinEntry.Reason.LINK_INVALID);
        }
        UserAccount membership = account.get();
        // ③ already completed — they joined, possibly on another device
        if (membership.getActivationTime() != null) {
            return JoinEntry.rejected(JoinEntry.Reason.ALREADY_JOINED);
        }
        // ④ closed or frozen while the invitation was outstanding
        if (membership.getStatus() == AccountStatus.DEACTIVATED
                || membership.getStatus() == AccountStatus.FROZEN) {
            return JoinEntry.rejected(JoinEntry.Reason.MEMBERSHIP_CLOSED);
        }
        // ⑤ usable — showing the account's CURRENT contacts (see resolveJoinContacts)
        JoinMobileFormat.Formatted mobile = mobileFormat.format(membership.getMobile());
        return JoinEntry.usable(
                tenantInfoService == null ? null : tenantInfoService.getTenantName(membership.getTenantId()),
                membership.getNickname(),
                ContactMasking.email(membership.getEmail()),
                mobile.masked(), mobile.dialCode(), mobile.countryName(),
                invitation.getPurpose() == InvitationPurpose.REINVITE);
    }

    /** Whether this join link was accepted, and by this very person: their own second tap. */
    private boolean acceptedBy(String rawToken, Long profileId) {
        return StringUtils.isNotBlank(rawToken) && this.searchOne(new Filters()
                        .eq(UserInvitation::getTokenHash, HashUtils.sha256Hex(rawToken)))
                .filter(invitation -> invitation.getStatus() == InvitationStatus.ACCEPTED
                        && invitation.getPurpose() != InvitationPurpose.PASSWORD_RESET)
                .flatMap(invitation -> accountService.getById(invitation.getUserId()))
                .map(account -> profileId.equals(account.getProfileId()))
                .orElse(false);
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public boolean isRebindLink(String rawToken) {
        return StringUtils.isNotBlank(rawToken) && this.searchOne(new Filters()
                        .eq(UserInvitation::getTokenHash, HashUtils.sha256Hex(rawToken)))
                .map(invitation -> invitation.getPurpose() == InvitationPurpose.REINVITE)
                .orElse(false);
    }

    /**
     * Returns the address stored ON the invitation, never one supplied by the caller. That is the
     * point: the caller only ever saw a masked value, and accepting an address from them would let
     * a link-holder redirect the code to themselves.
     *
     * <p>The send itself lives in {@code LoginService} — it already owns code issuance and rate
     * limiting, and it already depends on this service, so sending from here would close a cycle.
     */
    @SkipPermissionCheck
    @CrossTenant
    @Override
    public String resolveJoinChannel(String rawToken, String channel) {
        JoinContacts contacts = this.resolveJoinContacts(rawToken);
        String address = "mobile".equalsIgnoreCase(channel) ? contacts.mobile()
                : "email".equalsIgnoreCase(channel) ? contacts.email()
                        : null;
        if (address == null) {
            // Covers both "unknown channel" and "this invitation has no such contact" with one
            // message: telling a link-holder WHICH channels an invitation carries is itself a leak.
            throw new BusinessException("That verification channel is not available for this invitation.");
        }
        return address;
    }

    // @CrossTenant: public token-inspection endpoint has no tenant context — see acceptToken.
    @SkipPermissionCheck
    @CrossTenant
    @Override
    public InvitationInfo inspectToken(String rawToken) {
        if (StringUtils.isBlank(rawToken)) {
            return InvitationInfo.invalid();
        }
        Optional<UserInvitation> found = this.searchOne(
                new Filters().eq(UserInvitation::getTokenHash, HashUtils.sha256Hex(rawToken)));
        if (found.isEmpty()) {
            return InvitationInfo.invalid();
        }
        UserInvitation invitation = found.get();
        boolean usable = invitation.getStatus() == InvitationStatus.PENDING
                && (invitation.getExpiresAt() == null || invitation.getExpiresAt().isAfter(LocalDateTime.now()));
        return usable ? InvitationInfo.valid(invitation.getEmail(), invitation.getPurpose())
                      : InvitationInfo.invalid();
    }
}
