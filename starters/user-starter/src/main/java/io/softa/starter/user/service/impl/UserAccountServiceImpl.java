package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import java.time.LocalDateTime;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.SFunction;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MailRequestMessage;
import io.softa.framework.base.message.SmsRequestMessage;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.security.PasswordUtils;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.dto.UserAccountDTO;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.dto.WorkContacts;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserRoleRelService;
import io.softa.starter.user.service.UserProfileService;
import io.softa.starter.user.util.LoginIdentifiers;

/**
 * UserAccount Model Service Implementation
 */
@Slf4j
@Service
public class UserAccountServiceImpl extends EntityServiceImpl<UserAccount, Long> implements UserAccountService {

    /** The employee record, read by model name rather than through a shared contract — same
     *  convention as {@code UserAccessController} and the framework's EmployeeContextEnricher. */
    private static final String ARCHIVE_MODEL = "Employee";
    private static final String ARCHIVE_USER_FIELD = "userId";
    private static final String ARCHIVE_EMAIL_FIELD = "workEmail";
    private static final String ARCHIVE_MOBILE_FIELD = "workPhone";

    @Autowired
    private UserProfileService profileService;

    @Autowired
    private UserIdentityService identityService;

    /** @Lazy breaks the UserAccount ⇄ Login service cycle: LoginServiceImpl injects this service.
     *  Used only to ask the one question about whether a password is owed, so the rule has a single
     *  definition instead of a copy on each screen that asks. */
    @Autowired
    @Lazy
    private io.softa.starter.user.service.LoginService loginService;

    /** Role grants are cleared on off-boarding and on reviving a membership. */
    @Autowired
    private UserRoleRelService roleRelService;

    /** Notifies the OLD address when work contacts are reset — see resetWorkContacts. */
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /** Optional: the contact-change notice names the company; absent tenant-starter → blank. */
    @Autowired(required = false)
    private io.softa.framework.orm.service.TenantInfoService tenantInfoService;

    /**
     * Every single-entity account write funnels through here, so this is the one place that has to
     * drop the cached {@code UserInfo} — covering not just this class's own lock / unlock / password
     * paths but external callers too, above all {@code UserInvitationService.acceptToken}, which
     * flips INVITED → ACTIVE when the invitee sets their password. Missing that eviction left the
     * login gate (reads the account row) and {@code ContextBuilder} (reads this cache) disagreeing
     * for the cache's one-month TTL: the invitee authenticated successfully and was then bounced
     * back to the login screen by the next request, which saw {@code active=false}, cleared the
     * session, and reported "Invalid session ID". The reverse case is worse — a freshly frozen
     * account kept working, because the per-request status gate never saw the new status.
     */
    @Override
    public boolean updateOne(UserAccount entity) {
        boolean updated = super.updateOne(entity);
        if (updated && entity != null) {
            profileService.evictUserInfo(entity.getId());
        }
        return updated;
    }

    /**
     * The null-preserving twin — used where a write must clear a column (off-board releasing a
     * login identifier, unbind detaching a person, revive resetting activation). It has to evict
     * the cached UserInfo for the SAME reason the single-arg override does: unbind flips an account
     * to INVITED and detaches its person, but a stale cache would keep the mis-bound session alive
     * and active for the cache's month-long TTL — defeating the whole point of unbinding.
     */
    @Override
    public boolean updateOne(UserAccount entity, boolean ignoreNull) {
        boolean updated = super.updateOne(entity, ignoreNull);
        if (updated && entity != null) {
            profileService.evictUserInfo(entity.getId());
        }
        return updated;
    }

    // @CrossTenant: the company step runs BEFORE a tenant context exists — the whole point is to
    // list memberships ACROSS tenants so the person can pick one.
    @Override
    @CrossTenant
    public List<UserAccount> listMembershipsOf(Long profileId) {
        if (profileId == null) {
            return List.of();
        }
        return this.searchList(new Filters().eq(UserAccount::getProfileId, profileId)).stream()
                .filter(account -> account.getStatus() != AccountStatus.DEACTIVATED)
                .toList();
    }

    // @CrossTenant: login / forgot-password resolve an account by credential BEFORE a tenant
    // context exists (UserAccount is multiTenant); without it the ORM would filter by the absent
    // tenant and never find the account. Called only by other beans → the AOP proxy applies (no self).
    @Override
    @CrossTenant
    public Optional<UserAccount> getUserByEmail(String email) {
        Filters filters = new Filters().eq(UserAccount::getEmail, email);
        return this.searchOne(filters);
    }

    @Override
    @CrossTenant
    public Optional<UserAccount> getUserByMobile(String mobile) {
        Filters filters = new Filters().eq(UserAccount::getMobile, mobile);
        return this.searchOne(filters);
    }

    /**
     * Self-registration (OAuth only, since the email+password route was removed) creates
     * accounts with no tenant
     * context, which under multi-tenancy would land as tenant-less orphans. So it is allowed only
     * in single-tenant mode; multi-tenant deployments create/invite accounts via an administrator.
     */
    private void assertSelfRegistrationAllowed() {
        if (SystemConfig.env.isEnableMultiTenancy()) {
            throw new BusinessException(
                    "Self-registration is disabled in multi-tenant mode; accounts are created or invited by an administrator.");
        }
    }

    /**
     * A work contact as it is written to {@code UserAccount.email} / {@code mobile}: trimmed, case
     * kept. Every write path goes through here.
     *
     * <p>Trimmed because the columns are queried by equality — {@link #isWorkContactShared} and
     * {@link #findContactHolderInTenant} — with a value that has been trimmed, and a stored stray
     * space made the match miss: two accounts genuinely holding one address read as unshared, and
     * the shared-contact guard on code login, /join and reset let the address through. Case is kept
     * because the value is displayed as HR typed it; the equality queries rely on the columns'
     * MySQL {@code *_ci} collation to fold it.
     */
    private static String workContact(String contact) {
        // Trimmed, case kept (the *_ci collation folds case at the database); a mobile also loses
        // its typed separators, so the equality queries on these columns — the shared-contact guard
        // above all — see "+65 9123-4567" and "+6591234567" as the one number they are.
        return LoginIdentifiers.collapseMobile(StringUtils.trimToNull(contact));
    }

    private UserAccount buildUserAccount(UserAccountDTO accountInfo) {
        UserAccount userAccount = new UserAccount();
        userAccount.setEmail(workContact(accountInfo.getEmail()));
        userAccount.setMobile(workContact(accountInfo.getMobile()));
        userAccount.setUsername(accountInfo.getUsername());
        userAccount.setNickname(accountInfo.getNickname());
        userAccount.setStatus(AccountStatus.ACTIVE);
        // tenant_id is stamped by the framework on insert (UserAccount is multiTenant); a manual
        // set here would write null under the anonymous/cross-tenant context of self-registration.
        return userAccount;
    }

    /**
     * Register new user
     * Create user account and user profile, return UserInfo
     *
     * @param accountInfo User account information
     * @param profileInfo User profile information
     * @return UserInfo
     */
    // @SkipPermissionCheck: provisioning writes rows this call mints — the account, and through
    // registerUserProfile the person and their credentials. Row scope has no answer for ids that do
    // not exist yet, and fails closed on the anchorless ones. See registerUserProfile for the full
    // argument; the waiver covers provisioning only, never the business entity that asked for it.
    @SkipPermissionCheck
    @Override
    @Transactional
    public UserInfo registerNewUser(@NotNull UserAccountDTO accountInfo, @NotNull UserProfileDTO profileInfo) {
        assertSelfRegistrationAllowed();
        Assert.notBlank(accountInfo.getUsername(), "Username cannot be blank");
        try {
            // Create user account
            UserAccount userAccount = this.buildUserAccount(accountInfo);
            Long userId = this.createOne(userAccount);

            // Create user profile and return UserInfo
            return profileService.registerUserProfile(userId, profileInfo);
        } catch (Exception e) {
            throw new BusinessException("User registration failed: {0}", e.getMessage(), e);
        }
    }

    /**
     * Register new user (legacy method for backward compatibility)
     * Create user account and user profile, return UserInfo
     *
     * @param email Email
     * @param mobile Mobile
     * @param password Password
     * @return UserInfo
     */
    // @SkipPermissionCheck: the path that pairs an employee with a login. Without it, a role granted
    // "create employee" — and nothing on the user models, which the role wizard does not require it
    // to hold — creates the Employee row and then fails on the person behind it, leaving the caller
    // with a rolled-back create and an error naming a model they never asked to touch. See
    // registerUserProfile.
    @SkipPermissionCheck
    @Override
    @Transactional
    public UserInfo registerInvitedUser(String email, String mobile, String fullName) {
        NewAccountDecision decision = this.decideNewAccount(email, mobile);
        if (decision.refusal() != null) {
            throw new BusinessException(decision.refusal());
        }
        Long existingPerson = decision.existingPerson();

        // A leaver coming back: (tenantId, profileId) is unique, so a second row cannot be inserted
        // — the closed one is revived instead. The row is reset to PENDING with the new contacts,
        // so from here the person is treated exactly like any other fresh create: invite, /join.
        // Reached only when a LIVE login identifier named the person (see decideNewAccount); a
        // leaver whose identifiers were all released is re-hired explicitly, through rehire().
        if (decision.closedRow() != null) {
            UserAccount revived = this.reviveMembership(existingPerson, email, mobile)
                    .orElseThrow(() -> new BusinessException("This person is already a member of this company."));
            return profileService.getUserInfo(revived.getId());
        }

        UserAccountDTO accountInfo = new UserAccountDTO();
        UserProfileDTO profileInfo = new UserProfileDTO();
        accountInfo.setEmail(email);
        accountInfo.setMobile(mobile);
        // Username = email if present, else mobile (mirrors registerNewUser)
        String identifier = StringUtils.isNotBlank(email) ? email : mobile;
        accountInfo.setUsername(identifier);
        // Display name: caller-supplied fullName (e.g. an employee's real name) when present,
        // else fall back to the login identifier so account/profile display is never blank.
        String displayName = StringUtils.isNotBlank(fullName) ? fullName : identifier;
        accountInfo.setNickname(displayName);
        profileInfo.setFullName(displayName);

        UserAccount userAccount = this.buildUserAccount(accountInfo);
        // PENDING, no password. Creating an account does NOT contact anyone — the explicit
        // Invite action does, and that is what flips PENDING -> INVITED. Landing on INVITED
        // here would claim an invitation was sent when none was, which is exactly the
        // distinction the account list needs to show ("created" vs "contacted").
        userAccount.setStatus(AccountStatus.PENDING);
        Long userId = this.createOne(userAccount);

        // Their second company: link, do not mint. Note what is NOT carried over — the display name
        // stays whatever this caller supplied. Reading the existing person's name back would turn a
        // create form into a lookup for "who owns this address?", answerable by anyone who can
        // create an account. The account lands PENDING either way, so the person still has to
        // accept through /join before it becomes usable.
        if (existingPerson != null) {
            return profileService.linkAccountToPerson(userId, existingPerson);
        }
        return profileService.registerUserProfile(userId, profileInfo);
    }

    /**
     * What {@link #decideNewAccount} found: the person the contacts resolve to (or null), their
     * closed membership here when the create should revive it rather than insert, and the reason to
     * refuse — exactly one of which the create path needs at each step.
     */
    private record NewAccountDecision(Long existingPerson, Long closedRow, String refusal) {
        static NewAccountDecision refuse(String reason) {
            return new NewAccountDecision(null, null, reason);
        }
    }

    // @SkipPermissionCheck: the pre-check half of registerInvitedUser, asked by the same callers
    // (an import validating rows before it creates anything) with the same grants.
    @SkipPermissionCheck
    @Override
    public String newAccountRefusal(String email, String mobile) {
        return this.decideNewAccount(email, mobile).refusal();
    }

    /**
     * The one place that decides whether an account with these contacts may be created here.
     *
     * <p>Does this identifier already belong to somebody? If so this is their SECOND company, not a
     * duplicate: one person keeps one person record, and the account is linked to it instead of
     * minting a rival. Refusing that is what used to make "a person in two tenants" unreachable
     * through the product at all — every creation path funnels here, so the /join flow's own
     * find-or-create could never fire either: it needs an account carrying the person's identifier,
     * and that account was exactly what could not be created.
     *
     * <p>Within ONE tenant the identifiers stay unique — that is what uk_user_account_tenant_email
     * enforces, and a person already holding a live membership here is a duplicate rather than a
     * second company (uk_user_account_tenant_profile). Only the CROSS-tenant form of this rule was
     * relaxed.
     *
     * <p>The membership is resolved BEFORE the contact checks because a closed one changes what
     * they mean: a leaver's own old work address still sits on their DEACTIVATED row, and counting
     * it would refuse the very re-hire that row exists to be reused for.
     *
     * <p>A contact names a person ONLY through a live login identifier. A DEACTIVATED row holding
     * the contact is deliberately not read as "this is that person": a work address or a pool
     * phone is reissued to whoever comes next, so the row names the address's previous holder, not
     * the human being typed into the form — reviving it would hand the newcomer the leaver's
     * person record (and hand the operator the leaver's identity, which a create form must never
     * answer). Nor is it unique: nothing indexes (tenant, mobile), so two closed rows can carry one
     * number and any pick between them is a guess. When a closed row does hold the contact the
     * create is refused and the operator is pointed at the explicit {@link #rehire}, where the row
     * is named by id and the intent is stated rather than inferred.
     */
    private NewAccountDecision decideNewAccount(String email, String mobile) {
        Long tenantId = ContextHolder.getContext() == null ? null : ContextHolder.getContext().getTenantId();
        Long byEmail = this.personHolding(email);
        Long byMobile = this.personHolding(mobile);
        // A second identifier pointing at a DIFFERENT person is not a second company — it is two
        // people's credentials on one account, which no later step could untangle.
        if (byEmail != null && byMobile != null && !byEmail.equals(byMobile)) {
            return NewAccountDecision.refuse("This email and mobile belong to two different "
                    + "people. Enter contacts for one person.");
        }
        Long existingPerson = byEmail != null ? byEmail : byMobile;

        UserAccount membershipHere = existingPerson == null ? null
                : this.findMembershipInTenant(tenantId, existingPerson).orElse(null);
        Long closedRow = membershipHere != null && membershipHere.getStatus() == AccountStatus.DEACTIVATED
                ? membershipHere.getId() : null;
        String emailRefusal = this.contactRefusal(email, "Email", closedRow);
        if (emailRefusal != null) {
            return NewAccountDecision.refuse(emailRefusal);
        }
        String mobileRefusal = this.contactRefusal(mobile, "Mobile", closedRow);
        if (mobileRefusal != null) {
            return NewAccountDecision.refuse(mobileRefusal);
        }
        if (membershipHere != null && closedRow == null) {
            return NewAccountDecision.refuse(liveMembershipRefusal(membershipHere));
        }
        return new NewAccountDecision(existingPerson, closedRow, null);
    }

    /**
     * Why a live membership blocks the create — and, when it is a consultant's, that it IS one.
     *
     * <p>A consultant's membership is minted by the platform and hidden from every roster read
     * ({@code UserRosterScope} filters it out before anything else narrows), so the generic wording
     * sent an operator to look for a row they are structurally unable to see: the account list says
     * this company has no such member, and it never will. The message named a fact the UI is built
     * to contradict.
     *
     * <p>Saying "consultant" turns that dead end into something actionable — not by this operator,
     * who cannot administer these memberships by design, but by pointing at the side that can. The
     * refusal itself is unchanged: whether one person may be both a consultant and an employee of
     * one company is left undefined by the PRD (§0.1), and a message is not the place to decide it.
     */
    private String liveMembershipRefusal(UserAccount membership) {
        if (Boolean.TRUE.equals(membership.getConsultant())) {
            return "This person is a consultant for this company, so they cannot also be created "
                    + "as an employee here. Ask the platform administrator.";
        }
        return "This person is already a member of this company.";
    }

    /** The person whose live login identifier this contact is, or null when it is nobody's. */
    private Long personHolding(String contact) {
        if (StringUtils.isBlank(contact)) {
            return null;
        }
        return identityService.findByLoginIdentifier(contact)
                .map(UserIdentity::getProfileId)
                .orElse(null);
    }

    /** What an operator is told when a closed row of this tenant still carries the contact. */
    static final String REHIRE_INSTEAD = "A former employee's closed account still holds this "
            + "contact. Re-hire that account instead of creating a new one.";

    /**
     * Why {@code contact} cannot be given to a new account here, or null when it can. The person's
     * own closed row ({@code exceptClosedRow}) never counts — it is what a revive reuses.
     */
    private String contactRefusal(String contact, String label, Long exceptClosedRow) {
        if (StringUtils.isBlank(contact)) {
            return null;
        }
        return this.findContactHolderInTenant(contact, exceptClosedRow)
                // A closed row with a person behind it is re-hirable, so say so; a live row, or a
                // closed one nobody holds, is simply a duplicate of the address.
                .map(holder -> holder.getStatus() == AccountStatus.DEACTIVATED && holder.getProfileId() != null
                        ? REHIRE_INSTEAD
                        : label + " already exists: " + contact)
                .orElse(null);
    }

    // @SkipPermissionCheck / @CrossTenant for the same reasons offBoard carries them: re-hire is the
    // mirror of off-boarding, reached from HR's roster with whatever grants that role holds, and the
    // row is named by id from a roster that may span tenants.
    //
    // Residual, by design — the reissued contact. The revived row keeps the contacts it was closed
    // with, and the invitation HR sends next goes to them. If the person's identity is FULLY
    // released (no login identifier on either channel), /join rebinds the address that receives the
    // code onto that identity as a login identifier — so whoever now holds a reissued address (a
    // pool phone handed on, an old work mailbox) can claim the leaver's identity and every company
    // it still belongs to. An identity holding any live identifier is not rebound (see
    // LoginServiceImpl.reclaimLoginIdentifier), which leaves exactly the fully-released case open,
    // because there is no other evidence of who the person is. Mitigations are procedural: HR
    // corrects the row's contacts through Reset User BEFORE inviting, and the invitation is
    // HR-initiated — nobody can trigger it against a row they do not administer.
    @SkipPermissionCheck
    @Override
    @CrossTenant
    @Transactional
    public void rehire(Long accountId) {
        UserAccount account = this.getById(accountId)
                .orElseThrow(() -> new BusinessException("Account not found."));
        if (account.getStatus() != AccountStatus.DEACTIVATED) {
            throw new BusinessException("Only a closed account can be re-hired.");
        }
        if (account.getProfileId() == null) {
            throw new BusinessException(
                    "This closed account is not linked to a person, so there is nobody to re-hire.");
        }
        // The row's own contacts, not fresh ones: what is being reopened is THIS person's membership
        // as it was closed. HR corrects the contacts afterwards (Reset User) if they have changed,
        // and sends the invitation as a separate, visible step — reopening contacts nobody.
        //
        // The LOADED row is revived, not one re-queried by (profileId, ambient tenant). Re-hire is
        // reached from the roster, where a platform super-admin's ambient tenant is their own while
        // the row they named may sit in another company: the re-query found nothing there ("not
        // linked to a person") or, when the same person had also left the admin's company, revived
        // THAT closed row instead of the one asked for.
        this.revive(account, account.getEmail(), account.getMobile());
    }

    // @SkipPermissionCheck for the mirror of registerInvitedUser's reason. That one pairs an
    // employee with a login; this one unpairs them, and it is reached from HR approving a
    // resignation — a role granted "approve resignation", and nothing on the user models, which the
    // role wizard does not require it to hold. Left checked, the resignation would write the
    // employee's exit and then fail closed on the person behind it, leaving the membership Active
    // and its work address still a live login route: exactly the hole off-boarding exists to close.
    // What authorized this is the resignation approval, checked where it happened.
    @SkipPermissionCheck
    @Override
    @CrossTenant
    @Transactional
    public void offBoard(Long accountId) {
        UserAccount account = this.getById(accountId)
                .orElseThrow(() -> new BusinessException("Account not found."));
        if (offBoardWith(account)) {
            this.updateOne(account);
        }
    }

    /**
     * The three things off-boarding must do, on an already-loaded membership.
     *
     * <p>Separated from the lookup so the rules are testable without the ORM, and because their
     * ORDER is deliberate — see the comments inline.
     *
     * @return whether the membership changed (false when it was already closed)
     */
    boolean offBoardWith(UserAccount account) {
        if (account.getStatus() == AccountStatus.DEACTIVATED) {
            return false;   // idempotent: an HR workflow may legitimately fire twice
        }

        // ① Touch the person's credentials FIRST — release the work contact from their login
        // identifiers, and clear the password lock. Doing this before the status write means a
        // failure here cannot leave a closed membership whose address is still a live login route —
        // the order encodes which half is security-critical. One load and one write for both:
        // releaseLoginIdentifiers would read the same row again.
        identityService.findByProfile(account.getProfileId()).ifPresent(identity -> {
            boolean changed = applyIdentifierRelease(account, identity);
            // The lock defends against someone guessing THIS PERSON's password, and off-boarding is
            // not the moment to keep defending it: the lock is a PERSON-level fact while this
            // closes ONE membership, and a re-hire REVIVES the row, so a lock left standing greets
            // the returning employee as a refused password login with nothing in the UI to explain
            // it. Cleared unconditionally, exactly as freezeAccount does on the same transition
            // (PRD 2.1 "termination — clear the lock first → Deactivated").
            if (identity.getPasswordLockedUntil() != null) {
                identity.setPasswordLockedUntil(null);
                changed = true;
            }
            if (changed) {
                // updateOne(entity, false): both writes here are nulls, which the default
                // overload drops.
                identityService.updateOne(identity, false);
            }
            identityService.clearPasswordFailures(identity.getId());
        });

        // ② Clear role grants. A closed membership holding live grants is a standing hole by
        // itself, and since a re-hire REVIVES this row, anything left here is silently inherited.
        clearRoleGrants(account);

        // ③ Close the membership.
        account.setStatus(AccountStatus.DEACTIVATED);
        return true;
    }

    /** Notifies the person that their sign-in contact changed. */
    private static final String TEMPLATE_CONTACT_RESET = "user.contact-reset";

    @SkipPermissionCheck
    @CrossTenant
    @Override
    @Transactional
    public void resetWorkContacts(Long userId, String reason) {
        Assert.notNull(userId, "userId is required");
        // Read from the employee record, never from the caller (S-B / D23). Two things follow from
        // that, and the second is why it matters: the value cannot be whatever a client chose to
        // post, and the ACCOUNT still holds the value being replaced — which is exactly what gets
        // notified below. Propagating on the record's own edit instead would destroy that: by the
        // time HR pressed Confirm the old address would be gone, and the warning meant for whoever
        // still holds it would go to the new one.
        WorkContacts archive = this.archiveWorkContacts(userId);
        String email = workContact(archive.email());
        String mobile = workContact(archive.mobile());
        if (!archive.any()) {
            throw new BusinessException("This employee's record has no work email or work mobile — "
                    + "add one there first, then reset.");
        }

        UserAccount account = this.getById(userId)
                .orElseThrow(() -> new BusinessException("User not found."));
        // The right person holds this membership — that is what separates this from unbinding. So
        // there is nothing to do on an account nobody holds yet.
        if (account.getProfileId() == null) {
            throw new BusinessException(
                    "This account is not linked to a person yet — invite it instead.");
        }
        this.findContactHolderInTenant(email, userId)
                .ifPresent(other -> {
                    throw new BusinessException("That work email already belongs to another account.");
                });
        // The mobile too: it is a login identifier as much as the email is, and moving it onto
        // this account while another one holds it would leave a code sent there naming two people.
        this.findContactHolderInTenant(mobile, userId)
                .ifPresent(other -> {
                    throw new BusinessException("That work mobile already belongs to another account.");
                });

        String previousEmail = account.getEmail();
        String previousMobile = account.getMobile();

        // Move the LOGIN identifier with the contact, but only the value this company issued: a
        // personal login email is not ours to rewrite, so it compares before writing. Leaving it
        // behind would mean the person signs in with an address this company no longer knows, while
        // the recycled one becomes a route into their account for whoever receives it next.
        //
        // The identifier is written in LoginIdentifiers' canonical form while the account keeps the
        // contact as HR typed it: the account value is displayed, the identity value is looked up.
        identityService.findByProfile(account.getProfileId()).ifPresent(identity -> {
            boolean moved = false;
            if (isSameLoginIdentifier(previousEmail, identity.getLoginEmail())) {
                identity.setLoginEmail(LoginIdentifiers.normalize(email));
                moved = true;
            }
            if (isSameLoginIdentifier(previousMobile, identity.getLoginMobile())) {
                identity.setLoginMobile(LoginIdentifiers.normalize(mobile));
                moved = true;
            }
            if (moved) {
                // updateOne(entity, false): clearing one channel means writing a null, which the
                // default overload drops — the old identifier would stay a live login route.
                identityService.updateOne(identity, false);
            }
        });

        account.setEmail(email);
        account.setMobile(mobile);
        this.updateOne(account, false);

        // The OLD address, not the new one. If this was not the person's own doing, the message has
        // to reach somewhere they can still read; telling only the new address informs whoever now
        // holds it. Password and profileId are untouched, so there is nothing to re-accept — this
        // is a notification, not an invitation.
        // Notify the OLD contacts on EVERY channel the account had, mail AND SMS — the same
        // fan-out invitations use. A person reachable only by work mobile must still learn their
        // sign-in was changed; telling only the email would leave exactly them uninformed. The
        // variables match the user.contact-reset templates (M1): who the account belongs to, which
        // company changed it, and when. nickname (already on the account) stands in for the name
        // rather than loading the profile, which is more than a notification warrants.
        Long tenantId = ContextHolder.getContext().getTenantId();
        MessageScope scope = tenantId != null ? MessageScope.TENANT : MessageScope.PLATFORM;
        String companyName = tenantId == null || tenantInfoService == null ? ""
                : StringUtils.defaultString(tenantInfoService.getTenantName(tenantId));
        Map<String, Object> vars = Map.of(
                "employeeName", StringUtils.defaultString(account.getNickname()),
                "companyName", companyName,
                "time", LocalDateTime.now().toString());
        if (StringUtils.isNotBlank(previousEmail)) {
            eventPublisher.publishEvent(new MailRequestMessage(
                    List.of(previousEmail), TEMPLATE_CONTACT_RESET, vars, tenantId, scope));
        }
        if (StringUtils.isNotBlank(previousMobile)) {
            eventPublisher.publishEvent(new SmsRequestMessage(
                    List.of(previousMobile), TEMPLATE_CONTACT_RESET, vars));
        }
        log.info("Work contacts of account {} reset. Reason: {}", userId, reason);
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public Optional<UserAccount> findMembershipInTenant(Long tenantId, Long profileId) {
        if (tenantId == null || profileId == null) {
            return Optional.empty();
        }
        return this.searchList(new Filters()
                        .eq(UserAccount::getTenantId, tenantId)
                        .eq(UserAccount::getProfileId, profileId)).stream()
                .findFirst();
    }

    /**
     * <p>The tenant is filtered EXPLICITLY rather than left to the ORM's ambient stamp. This class
     * carries {@code @CrossTenant} on the credential lookups, and {@code UserAccountController}
     * sets the flag for a platform super-admin working a roster that spans tenants — while it is
     * set the ORM skips tenant filtering entirely, so an ambient-scoped query here would quietly
     * widen back into the cross-tenant check this narrows.
     *
     * <p>A contact is looked for under BOTH columns: HR moving an address between the email and
     * mobile fields of two accounts is the same collision, and checking only the matching column
     * would let the write reach the index instead.
     */
    @SkipPermissionCheck
    @CrossTenant
    @Override
    public Optional<UserAccount> findContactHolderInTenant(String contact, Long exceptAccountId) {
        Long tenantId = ContextHolder.getContext() == null ? null : ContextHolder.getContext().getTenantId();
        return this.contactHolderInTenant(tenantId, contact, exceptAccountId);
    }

    /**
     * The tenant-explicit form of {@link #findContactHolderInTenant}, for a caller holding a row
     * whose tenant is not the ambient one (re-hire from a roster spanning tenants). Same question,
     * asked of the company the ROW belongs to rather than the company the operator is working in.
     */
    private Optional<UserAccount> contactHolderInTenant(Long tenantId, String contact, Long exceptAccountId) {
        // Trimmed to the form the columns hold (workContact). Not lowercased: case-insensitivity of
        // this equality is the MySQL *_ci collation of the email / mobile columns — a known,
        // accepted dependency, the same one every other equality on these columns already has.
        List<String> spellings = LoginIdentifiers.workContactSpellings(contact);
        if (spellings.isEmpty() || tenantId == null) {
            // No tenant means no scope to be unique within, so nothing is taken.
            return Optional.empty();
        }
        return Stream.of(
                        this.contactHolder(tenantId, UserAccount::getEmail, spellings),
                        this.contactHolder(tenantId, UserAccount::getMobile, spellings))
                .flatMap(Optional::stream)
                .filter(other -> exceptAccountId == null || !other.getId().equals(exceptAccountId))
                .findFirst();
    }

    @SkipPermissionCheck
    @Override
    public WorkContacts archiveWorkContacts(Long userId) {
        // No Employee model at all (a deployment that is not an HR one) → nothing to follow.
        if (userId == null || !ModelManager.existModel(ARCHIVE_MODEL)) {
            return WorkContacts.none();
        }
        // Read past row scope on purpose: what is being read is the contact detail of the very
        // account the caller is already operating on, resolved from its id — not a search. A role
        // that may reset an account but holds nothing on Employee would otherwise see two blanks
        // and be told the record has no contacts.
        Context ctx = ContextHolder.existContext() ? ContextHolder.getContext() : null;
        boolean previous = ctx != null && ctx.isSkipPermissionCheck();
        if (ctx != null) {
            ctx.setSkipPermissionCheck(true);
        }
        try {
            Map<String, Object> row = modelService.searchOne(ARCHIVE_MODEL,
                    new FlexQuery(new Filters().eq(ARCHIVE_USER_FIELD, userId))).orElse(null);
            if (row == null) {
                return WorkContacts.none();
            }
            return new WorkContacts(
                    StringUtils.trimToNull(asString(row.get(ARCHIVE_EMAIL_FIELD))),
                    StringUtils.trimToNull(asString(row.get(ARCHIVE_MOBILE_FIELD))));
        } catch (Throwable t) {
            // Degrade to "no record" rather than failing the operation that asked: the caller
            // refuses on its own when there is nothing to reach the person on, with a message that
            // says so, which is a better answer than a stack trace about a model they never named.
            log.warn("Employee read failed while resolving work contacts for account {}; "
                    + "treating as no employee record", userId, t);
            return WorkContacts.none();
        } finally {
            if (ctx != null) {
                ctx.setSkipPermissionCheck(previous);
            }
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Optional<UserAccount> contactHolder(Long tenantId, SFunction<UserAccount, ?> field, List<String> spellings) {
        return this.searchList(contactFilter(field, spellings)
                .eq(UserAccount::getTenantId, tenantId)).stream().findFirst();
    }

    /**
     * The equality on a work-contact column, asked under every spelling the value may be stored in.
     *
     * <p>Rows written before the separator fold hold a mobile as HR typed it ("+65 9123-4567"), and
     * no migration rewrites them. One collapsed spelling would miss those rows on exactly the
     * questions these columns answer — is this number shared, does this tenant already hold it —
     * so a mobile is asked for as a set (LoginIdentifiers.workContactSpellings); an email has one
     * spelling and stays an exact match.
     */
    private static Filters contactFilter(SFunction<UserAccount, ?> field, List<String> spellings) {
        return spellings.size() == 1
                ? new Filters().eq(field, spellings.getFirst())
                : new Filters().in(field, spellings);
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public boolean isWorkContactShared(String contact) {
        // The callers hold a login identifier in its typed form; the question is asked of the
        // work contacts, which are stored trimmed with HR's case (workContact). The spellings are
        // trimmed here to that form; case-insensitivity of the equality is the MySQL *_ci collation
        // of the email / mobile columns — a known, accepted dependency, not something this method
        // could supply by lowercasing the query (the stored value is not lowercased).
        List<String> spellings = LoginIdentifiers.workContactSpellings(contact);
        if (spellings.isEmpty()) {
            return false;
        }
        // Counts PEOPLE, not accounts. One person employed by two tenants has two accounts
        // carrying the same personal address, and that is the whole point of multi-company — it
        // must not read as "shared". What makes an address unusable for identification is that it
        // could resolve to more than one PERSON.
        //
        // An account with no person yet counts as its own potential person: the shared-number
        // attack is exactly a bound holder plus an unbound account on the same number, where
        // resolving the address hands the unbound holder the bound one's identity. The cost is
        // that a genuine cross-company invite (a second company creating an account with the
        // person's own address, before they join) also reads as ambiguous until they join —
        // deliberate: the two are indistinguishable from the data, and the password path is
        // unaffected, so that person can still sign in.
        //
        // Across tenants: the same number handed to workers in two tenants is still one number.
        //
        // Across spellings, too: a row written as "+65 9123-4567" before the fold and one written
        // as "+6591234567" after it are the one number held twice (contactFilter), and the people
        // behind them are counted together exactly as they are across rows.
        List<UserAccount> matches = new ArrayList<>(
                this.searchList(contactFilter(UserAccount::getEmail, spellings)));
        matches.addAll(this.searchList(contactFilter(UserAccount::getMobile, spellings)));

        Set<Long> boundPeople = new HashSet<>();
        long unbound = 0;
        Set<Long> seenAccounts = new HashSet<>();
        for (UserAccount account : matches) {
            if (account.getId() != null && !seenAccounts.add(account.getId())) {
                continue;   // an account whose email AND mobile are the same string
            }
            if (account.getProfileId() == null) {
                unbound++;
            } else {
                boundPeople.add(account.getProfileId());
            }
        }
        return boundPeople.size() + unbound > 1;
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public boolean releaseLoginIdentifiers(UserAccount account) {
        if (account == null) {
            return false;
        }
        return identityService.findByProfile(account.getProfileId()).map(identity -> {
            boolean released = applyIdentifierRelease(account, identity);
            if (released) {
                // updateOne(entity) drops null keys — the one thing this write is FOR. The
                // overload that keeps them is not optional here.
                identityService.updateOne(identity, false);
            }
            return released;
        }).orElse(false);
    }

    /**
     * Null out the login identifiers this company issued, on an already-loaded identity, WITHOUT
     * writing it.
     *
     * <p>Split from {@link #releaseLoginIdentifiers} so off-boarding — which also clears the
     * password lock on the same row — can do both in one read and one write instead of two of each.
     *
     * @return whether anything was released (i.e. whether the row needs writing)
     */
    private boolean applyIdentifierRelease(UserAccount account, UserIdentity identity) {
        boolean released = false;
        if (isSameLoginIdentifier(account.getEmail(), identity.getLoginEmail())) {
            identity.setLoginEmail(null);
            released = true;
        }
        if (isSameLoginIdentifier(account.getMobile(), identity.getLoginMobile())) {
            identity.setLoginMobile(null);
            released = true;
        }
        if (released) {
            log.info("Released work contact(s) from identity {} of account {}.",
                    identity.getId(), account.getId());
        }
        return released;
    }

    /**
     * Whether a work contact and a login identifier name the same address.
     *
     * <p>Compared in LoginIdentifiers' canonical form on BOTH sides: the contact is stored as HR
     * typed it, the identifier as the seeding wrote it, and a comparison that trusted either
     * spelling would leave an identifier this company issued in place when the contact moves or the
     * membership closes — a live login route into an address about to be handed to someone else.
     */
    private static boolean isSameLoginIdentifier(String workContact, String loginIdentifier) {
        String contact = LoginIdentifiers.normalize(workContact);
        return contact != null && contact.equals(LoginIdentifiers.normalize(loginIdentifier));
    }

    @Override
    @CrossTenant
    @Transactional
    public Optional<UserAccount> reviveMembership(Long profileId, String workEmail, String workMobile) {
        if (profileId == null) {
            return Optional.empty();
        }
        // Scoped to THIS company. @CrossTenant lets the query run before a membership is chosen, but
        // a person may have left several tenants — searching by profile alone would revive whichever
        // closed row came first, possibly in another tenant. Re-hire happens inside one company's HR
        // context, so that is the tenant this reuses.
        Long tenantId = ContextHolder.getContext() == null ? null : ContextHolder.getContext().getTenantId();
        Assert.notNull(tenantId, "Re-hire must run within a company context.");
        Optional<UserAccount> closed = this.searchList(new Filters()
                        .eq(UserAccount::getProfileId, profileId)
                        .eq(UserAccount::getTenantId, tenantId)
                        .eq(UserAccount::getStatus, AccountStatus.DEACTIVATED)).stream()
                .findFirst();
        return closed.map(account -> this.revive(account, workEmail, workMobile));
    }

    /**
     * Revive an already-loaded closed membership, checked and written within ITS company.
     *
     * <p>The one path both re-hire shapes share, so the rules cannot drift between them. The tenant
     * is the row's, not the operator's: {@link #reviveMembership} has already scoped its lookup, and
     * {@link #rehire} holds a row that may belong to another company than the ambient one.
     *
     * <p>BOTH contacts are checked against other holders in that company. A work mobile is a
     * contact on the same footing as the email — Reset User already refuses a number another account
     * holds — and reviving a row with a number a colleague's row carries would leave a code sent
     * there naming two people. Refused here rather than at the index, which only covers the email.
     */
    private UserAccount revive(UserAccount account, String workEmail, String workMobile) {
        Long tenantId = account.getTenantId();
        Assert.notNull(tenantId, "A membership must belong to a company to be revived.");
        this.contactHolderInTenant(tenantId, workEmail, account.getId())
                .ifPresent(other -> {
                    throw new BusinessException("That work email already belongs to another account.");
                });
        this.contactHolderInTenant(tenantId, workMobile, account.getId())
                .ifPresent(other -> {
                    throw new BusinessException("That work mobile already belongs to another account.");
                });
        reviveWith(account, workEmail, workMobile);
        // updateOne(entity, false): reviveWith clears activationTime to null, which the default
        // overload drops — the revived PENDING row would otherwise keep the previous stint's
        // activation timestamp.
        this.updateOne(account, false);
        log.info("Revived membership {} for profile {} — reset to PENDING, awaiting a new invitation.",
                account.getId(), account.getProfileId());
        return account;
    }

    /**
     * Reset a closed membership for someone re-joining, on an already-loaded row.
     *
     * <p>Nothing from the previous stint may leak into the new one: new work contacts, no
     * activation (they must accept a fresh invitation), no roles. The employment history that DOES
     * carry over lives on the employee record, which is created anew.
     */
    void reviveWith(UserAccount account, String workEmail, String workMobile) {
        account.setEmail(workContact(workEmail));
        account.setMobile(workContact(workMobile));
        account.setStatus(AccountStatus.PENDING);
        account.setActivationTime(null);
        clearRoleGrants(account);
    }

    /**
     * Drop every role grant held by a membership.
     *
     * <p>Both the {@code roles} field and the {@code UserRoleRel} rows: the former is what the UI
     * edits, the latter is what the permission snapshot reads, and leaving either behind means
     * "no roles" in one place and live grants in the other.
     */
    private void clearRoleGrants(UserAccount account) {
        roleRelService.deleteByFilters(new Filters().eq("userId", account.getId()));
        if (account.getRoles() != null && !account.getRoles().isEmpty()) {
            account.setRoles(List.of());
        }
    }

    @Override
    @Transactional
    public void changeMyPassword(String currentPassword, String newPassword) {
        Assert.notBlank(currentPassword, "Old password cannot be empty.");
        Assert.notBlank(newPassword, "New password cannot be empty.");
        // Strength (B7) is enforced inside identityService.setPassword, the choke point every
        // password write passes through — not repeated here.

        Long userId = ContextHolder.getContext().getUserId();
        Assert.notNull(userId, "Cannot change password without logged-in user context.");

        UserAccount user = this.getById(userId).orElseThrow(() -> new BusinessException("Current user not found."));

        // The password is the PERSON's, so changing it here changes it for every company they
        // belong to. That is the intended meaning of one global credential.
        UserIdentity identity = identityService.requireIdentity(user);
        if (!identityService.matchesPassword(identity, currentPassword)) {
            throw new BusinessException("Incorrect old password.");
        }
        if (identityService.matchesPassword(identity, newPassword)) {
            throw new BusinessException("New password cannot be the same as the old password.");
        }
        identityService.setPassword(identity.getId(), newPassword);

        log.info("User ID {} changed their password successfully (identity {}).", userId, identity.getId());
    }

    @Override
    public boolean mustSetMyPassword() {
        Long userId = ContextHolder.getContext().getUserId();
        if (userId == null) {
            // No session, nothing owed — the caller is not the person this could apply to.
            return false;
        }
        // Delegated rather than decided here. This used to be its own blank-password test, which is
        // how a consultant still met the set-password wall after logging in: the login response said
        // no and this said yes, and the login page ORs the two. Whether somebody must set a password
        // has one answer, and LoginService owns it — a consultant is exempt (PRD C1), a person with
        // no credentials row at all is not forced into a screen the set-password call would refuse.
        return this.getById(userId)
                .map(UserAccount::getProfileId)
                .map(loginService::mustSetPassword)
                .orElse(false);
    }

    @Override
    @Transactional
    public void setMyFirstPassword(String newPassword) {
        Assert.notBlank(newPassword, "New password cannot be empty.");
        Long userId = ContextHolder.getContext().getUserId();
        Assert.notNull(userId, "Cannot set a password without logged-in user context.");

        UserAccount user = this.getById(userId)
                .orElseThrow(() -> new BusinessException("Current user not found."));
        UserIdentity identity = identityService.requireIdentity(user);
        if (StringUtils.isNotBlank(identity.getPassword())) {
            // Whoever holds this session already had a way in that they must prove again. Letting
            // the call through here would turn "I forgot my password" into "I am already inside",
            // which is the one thing the change-password flow exists to prevent.
            throw new BusinessException(
                    "A password is already set — use change password instead.");
        }
        // Strength is enforced inside setPassword, against this person's own contact details.
        identityService.setPassword(identity.getId(), newPassword);

        log.info("User ID {} set their first password (identity {}).", userId, identity.getId());
    }

    @Override
    @Transactional
    public boolean forceResetPassword(Long userId, String newPassword) {
        Assert.notBlank(newPassword, "New password cannot be empty.");
        // Strength (B7) is enforced inside identityService.setPassword — see changeMyPassword.
        UserAccount user = this.getById(userId).orElseThrow(() -> new BusinessException("User not found."));
        identityService.setPassword(user, newPassword);

        log.info("User ID {} password was reset by admin.", userId);
        return true;
    }

    @Override
    @Transactional
    public void freezeAccount(Long userId, String reason) {
        UserAccount user = this.getById(userId).orElseThrow(() -> new BusinessException("User not found."));
        if (user.getStatus() == AccountStatus.FROZEN) {
            return;   // idempotent: an HR workflow may legitimately fire twice
        }
        if (user.getStatus() != AccountStatus.ACTIVE && user.getStatus() != AccountStatus.LOCKED) {
            throw new BusinessException("Only an active account can be frozen.");
        }
        // Clear the password lock on the way through (§2.3 "Freeze on Locked — clear the lock
        // first"). Leaving it would mean that lifting the freeze hands back an account the lockout
        // still refuses, and the administrator who lifted it cannot see why.
        identityService.findByProfile(user.getProfileId()).ifPresent(identity -> {
            if (identity.getPasswordLockedUntil() != null) {
                identity.setPasswordLockedUntil(null);
                // updateOne(entity, false): clearing the lock means writing a null, which the
                // default overload drops.
                identityService.updateOne(identity, false);
            }
            identityService.clearPasswordFailures(identity.getId());
        });
        user.setStatus(AccountStatus.FROZEN);
        this.updateOne(user);
        log.info("Account {} frozen. Reason: {}", userId, reason);
    }

    @Override
    @Transactional
    public void unfreezeAccount(Long userId, String reason) {
        UserAccount user = this.getById(userId).orElseThrow(() -> new BusinessException("User not found."));
        if (user.getStatus() != AccountStatus.FROZEN) {
            // Not idempotent-by-silence on purpose: an INVITED or DEACTIVATED account flipped to
            // ACTIVE here would land in a state its own flow never reaches — invited-but-active,
            // or off-boarded-but-active.
            throw new BusinessException("Only a frozen account can be unfrozen.");
        }
        user.setStatus(AccountStatus.ACTIVE);
        this.updateOne(user);
        log.info("Account {} unfrozen. Reason: {}", userId, reason);
    }

    @Override
    @Transactional
    public void unfreezeAccounts(List<Long> userIds, String reason) {
        Filters filters = new Filters().in(UserAccount::getId, userIds)
                // Bulk path, so the per-row guard above cannot run — the filter carries it instead.
                // Without this a bulk "unfreeze everything selected" would activate invited and
                // off-boarded rows caught in the selection.
                .eq(UserAccount::getStatus, AccountStatus.FROZEN);
        UserAccount updateEntity = new UserAccount();
        updateEntity.setStatus(AccountStatus.ACTIVE);
        this.updateByFilter(filters, updateEntity);
        // updateByFilter bypasses updateOne, so evict here as well — otherwise a bulk-unlocked
        // account stays `active=false` in the cache and every request terminates its session.
        if (userIds != null) {
            userIds.forEach(profileService::evictUserInfo);
        }
    }
}