package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
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
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.starter.user.enums.LoginEvent;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.enums.SecurityLogSource;
import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserSecurityLogService;
import io.softa.starter.user.service.UserSessionService;
import io.softa.starter.user.spi.PushDeviceRegistry;
import io.softa.starter.user.constant.AccountErrorCodes;
import io.softa.starter.user.util.ClientInfo;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserRoleRelService;
import io.softa.starter.user.service.UserProfileService;
import io.softa.starter.user.util.LoginIdentifiers;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.service.LoginService;

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
    private static final String ARCHIVE_PROFILE_FIELD = "employeeProfileId";
    private static final String ARCHIVE_PROFILE_MODEL = "EmployeeProfile";
    private static final String ARCHIVE_PERSONAL_MOBILE_FIELD = "personalPhone";

    @Autowired
    private UserProfileService profileService;

    @Autowired
    private UserIdentityService identityService;

    /** @Lazy breaks the UserAccount ⇄ Login service cycle: LoginServiceImpl injects this service.
     *  Used only to ask the one question about whether a password is owed, so the rule has a single
     *  definition instead of a copy on each screen that asks. */
    @Autowired
    @Lazy
    private LoginService loginService;

    @Autowired
    private UserSessionService sessionService;

    /** Optional: present only where APP push is deployed. */
    @Autowired(required = false)
    private PushDeviceRegistry pushDeviceRegistry;

    @Autowired
    private UserSecurityLogService securityLog;

    @Autowired
    private LoginHistoryRecorder loginHistory;

    /** Masks the numbers the security log records, in the one form shown everywhere. */
    @Autowired
    private JoinMobileFormat mobileFormat;

    /** @Lazy: the invitation service reads and writes accounts through this one. */
    @Autowired
    @Lazy
    private UserInvitationService invitationService;

    /** Role grants are cleared on off-boarding and on reviving a membership. */
    @Autowired
    private UserRoleRelService roleRelService;

    /** Notifies the OLD address when work contacts are reset — see resetWorkContacts. */
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /** Optional: the contact-change notice names the company; absent tenant-starter → blank. */
    @Autowired(required = false)
    private TenantInfoService tenantInfoService;

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

    /**
     * Run a query across every tenant, whoever calls it.
     *
     * <p>{@code @CrossTenant} is an aspect, so it only applies when the call comes through the Spring
     * proxy. This class calls its own membership lookups too — a create deciding whether a number is
     * dormant, a release checking the holder — and through {@code this} the annotation is silently
     * skipped: the query is filtered by the caller's tenant, and a person who is still employed in
     * another company reads as having left everywhere. The lookups that must span tenants therefore
     * say so in their body, where no call path can drop it.
     */
    private static <T> T acrossTenants(Supplier<T> query) {
        Context context = ContextHolder.cloneContext();
        context.setCrossTenant(true);
        context.setSkipPermissionCheck(true);
        return ContextHolder.callWith(context, query::get);
    }

    // Across tenants: the company step runs BEFORE a tenant context exists — the whole point is to
    // list memberships ACROSS tenants so the person can pick one.
    @Override
    @CrossTenant
    public List<UserAccount> listMembershipsOf(Long profileId) {
        if (profileId == null) {
            return List.of();
        }
        return acrossTenants(() -> this.searchList(new Filters().eq(UserAccount::getProfileId, profileId))).stream()
                .filter(account -> account.getStatus() != AccountStatus.DEACTIVATED)
                .toList();
    }

    @Override
    @CrossTenant
    @SkipPermissionCheck
    public List<Long> listAccountIdsOf(Long profileId) {
        if (profileId == null) {
            return List.of();
        }
        return acrossTenants(() -> this.searchList(new Filters().eq(UserAccount::getProfileId, profileId))).stream()
                .map(UserAccount::getId)
                .toList();
    }

    /** The statuses of an account its person has joined — the ones whose email signs them in. */
    static final Set<AccountStatus> JOINED_STATUSES =
            EnumSet.of(AccountStatus.ACTIVE, AccountStatus.FROZEN, AccountStatus.LOCKED);

    /** One address: something before and after a single {@code @}, a dot in the domain, no spaces. */
    private static final java.util.regex.Pattern EMAIL_SHAPE =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Override
    @CrossTenant
    @SkipPermissionCheck
    public Set<Long> findJoinedProfilesByEmail(String email) {
        String wanted = StringUtils.trimToNull(email);
        // Reached from anonymous login screens: anything that is not one address is answered with
        // nobody rather than turned into a query.
        if (wanted == null || !EMAIL_SHAPE.matcher(wanted).matches()) {
            return Set.of();
        }
        return acrossTenants(() -> this.accountsWithEmail(new Filters()
                        .in(UserAccount::getStatus, List.copyOf(JOINED_STATUSES)), wanted)).stream()
                .filter(account -> account.getProfileId() != null)
                .map(UserAccount::getProfileId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * The accounts among {@code base} whose email is {@code email}, case aside.
     *
     * <p>Email case is not significant and the column keeps the address as HR typed it, so the
     * equality cannot be a plain {@code =} on PostgreSQL. The query is the dialect's case-insensitive
     * prefix match (ILIKE there) with the address's own {@code %} and {@code _} escaped — unescaped, an
     * identifier of {@code %@%} would match every row — and the exact, case-folded comparison here is
     * what decides.
     */
    private List<UserAccount> accountsWithEmail(Filters base, String email) {
        return this.searchList(base.startWith(UserAccount::getEmail, likeLiteral(email))).stream()
                .filter(account -> email.equalsIgnoreCase(StringUtils.trim(account.getEmail())))
                .toList();
    }

    /** A value matched literally inside a LIKE pattern: its wildcards and the escape character escaped. */
    static String likeLiteral(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
        NewAccountDecision decision = this.decideNewAccount(email, mobile, true);
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
            // Nobody is behind the reopened row until /join binds them, so there is no person to
            // build a full UserInfo from — the caller only needs the account it was given.
            UserInfo reopened = new UserInfo();
            reopened.setUserId(revived.getId());
            reopened.setTenantId(revived.getTenantId());
            return reopened;
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
        // A pre-check writes nothing: a dormant number is reported as free, and released only by the
        // create that follows.
        return this.decideNewAccount(email, mobile, false).refusal();
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
     * they mean: a leaver's DEACTIVATED row may still carry their work mobile, and counting it would
     * refuse the very re-hire that row exists to be reused for.
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
    private NewAccountDecision decideNewAccount(String email, String mobile, boolean release) {
        Long tenantId = ContextHolder.getContext() == null ? null : ContextHolder.getContext().getTenantId();
        Long byEmail = this.personHolding(email);
        Long byMobile = this.personHolding(mobile);
        // A number whose holder has left every company is most likely a recycled one: the new
        // employee must not be filed under the leaver's person. Released, and a new person is made.
        // Except when the holder left THIS company: that is the leaver coming back with their own
        // number, and the closed row below is revived for them.
        if (byMobile != null && !byMobile.equals(byEmail) && this.listMembershipsOf(byMobile).isEmpty()
                && !this.belongedToTenant(tenantId, byMobile)
                && (!release || this.releaseDormantLoginMobile(mobile, "Account created"))) {
            byMobile = null;
        }
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
     * one company is left undefined by the requirement, and a message is not the place to decide it.
     */
    private String liveMembershipRefusal(UserAccount membership) {
        if (Boolean.TRUE.equals(membership.getConsultant())) {
            return "This person is a consultant for this company, so they cannot also be created "
                    + "as an employee here. Ask the platform administrator.";
        }
        return "This person is already a member of this company.";
    }

    /**
     * Whether this person has a row in this company: their closed membership, or one reopened for
     * their re-hire that nobody has joined yet.
     */
    private boolean belongedToTenant(Long tenantId, Long profileId) {
        if (tenantId == null || profileId == null) {
            return false;
        }
        return this.findMembershipInTenant(tenantId, profileId).isPresent()
                || !acrossTenants(() -> this.searchList(new Filters()
                        .eq(UserAccount::getTenantId, tenantId)
                        .eq(UserAccount::getFormerProfileId, profileId))).isEmpty();
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
    // Who comes back is decided at /join, not here: the reopened row remembers its person, and the
    // number verified there either is that person's login mobile (bound back) or is not (a new
    // person, or the holder's own account, as for any invitation).
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
        if (account.getProfileId() == null && account.getFormerProfileId() == null) {
            throw new BusinessException(
                    "This closed account is not linked to a person, so there is nobody to re-hire.");
        }
        // The contacts come from the employee record: closing the account cleared its enterprise
        // email, and the invitation goes to the personal mobile. The row's own values are the
        // fallback for a deployment without employee records. HR sends the invitation as a
        // separate, visible step — reopening contacts nobody.
        //
        // The LOADED row is revived, not one re-queried by (profileId, ambient tenant). Re-hire is
        // reached from the roster, where a platform super-admin's ambient tenant is their own while
        // the row they named may sit in another company: the re-query found nothing there ("not
        // linked to a person") or, when the same person had also left the admin's company, revived
        // THAT closed row instead of the one asked for.
        String email = StringUtils.defaultIfBlank(this.archiveWorkContacts(accountId).email(), account.getEmail());
        String mobile = StringUtils.defaultIfBlank(this.archivePersonalMobile(accountId), account.getMobile());
        this.revive(account, email, mobile);
    }

    @SkipPermissionCheck
    @Override
    @CrossTenant
    @Transactional
    public Optional<UserAccount> reviveAccount(Long accountId, String workEmail, String workMobile) {
        // By the account's own id, not by the person behind it: a row reopened once and closed again
        // before anyone joined has no person, and would otherwise never be found again.
        return this.getById(accountId)
                .filter(account -> account.getStatus() == AccountStatus.DEACTIVATED)
                .map(account -> this.revive(account, workEmail, workMobile));
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
            // updateOne(entity, false): closing clears the enterprise email, a null the default
            // overload drops.
            this.updateOne(account, false);
        }
    }

    /**
     * What off-boarding must do, on an already-loaded membership.
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
        AccountStatus previous = account.getStatus();
        // ① The person's credentials. The login mobile is the person's own number and stays —
        // except when it is THIS company's work mobile (people created before the login mobile was
        // taken from the personal one): a company number is handed to the next hire, so it is taken
        // off the person's login. The enterprise email needs nothing: email login reads joined
        // accounts, so it stops working the moment this one closes.
        identityService.findByProfile(account.getProfileId()).ifPresent(identity -> {
            String workMobile = this.archiveWorkContacts(account.getId()).mobile();
            String releasedMobile = null;
            if (isSameLoginIdentifier(workMobile, identity.getLoginMobile())) {
                releasedMobile = identity.getLoginMobile();
                identity.setLoginMobile(null);
                identityService.updateLoginMobile(identity.getId(), null);
            }
            // The lock defends against someone guessing THIS PERSON's password, and off-boarding is
            // not the moment to keep defending it: the lock is a PERSON-level fact while this
            // closes ONE membership, and a re-hire REVIVES the row, so a lock left standing greets
            // the returning employee as a refused password login with nothing in the UI to explain
            // it. Cleared unconditionally, exactly as freezeAccount does on the same transition
            // (termination clears the lock first, then closes the membership).
            if (identity.getPasswordLockedUntil() != null) {
                identity.setPasswordLockedUntil(null);
                identityService.clearPasswordLock(identity.getId());
            }
            identityService.clearPasswordFailures(identity.getId());
            if (releasedMobile != null) {
                UserSecurityLog released = securityLog.entry(SecurityAction.WORK_MOBILE_RELEASED,
                        SecurityLogSource.SYSTEM, account, null);
                released.setOldValue(mobileFormat.mask(releasedMobile));
                securityLog.record(released);
            }
        });
        // ② Clear role grants. A closed membership holding live grants is a standing hole by
        // itself, and since a re-hire REVIVES this row, anything left here is silently inherited.
        clearRoleGrants(account);
        // ③ Links not yet used die with the membership.
        invitationService.revokeOutstandingLinks(account.getId());
        // ④ The enterprise email goes with the job: the mailbox is the company's to give to the next
        // hire, and an address left on a closed row would keep blocking that hire's account. The old
        // value is kept in the security log; a re-hire takes the address from the employee record.
        if (StringUtils.isNotBlank(account.getEmail())) {
            UserSecurityLog cleared = securityLog.entry(SecurityAction.ENTERPRISE_EMAIL_CHANGED,
                    SecurityLogSource.SYSTEM, account, null);
            cleared.setOldValue(ContactMasking.email(account.getEmail()));
            cleared.setReason("Deactivated");
            securityLog.record(cleared);
            // Kept for the account list only, which still shows what the closed account had.
            account.setClosedEmail(account.getEmail());
            account.setEmail(null);
        }
        // ⑤ Close the membership, and every session signed into it: a closed account may be reopened
        // for someone else, and a session left alive would be theirs the moment they join.
        account.setStatus(AccountStatus.DEACTIVATED);
        this.logStatusChange(account, previous, AccountStatus.DEACTIVATED, SecurityLogSource.SYSTEM, null);
        sessionService.revokeAccount(account.getId(), SessionRevokeReason.ORG_UNAVAILABLE);
        return true;
    }

    @Override
    @CrossTenant
    @SkipPermissionCheck
    @Transactional
    public boolean releaseDormantLoginMobile(String mobile, String scenario) {
        if (StringUtils.isBlank(mobile) || mobile.contains("@")) {
            return false;
        }
        UserIdentity holder = identityService.findByLoginIdentifier(mobile).orElse(null);
        // listMembershipsOf leaves Deactivated out: anything it returns — Active, Frozen, Pending,
        // Invited — is a person still in some company, whose number stays theirs. So is a person a
        // company has reopened a row for and is waiting to have back: /join recognises them by
        // this very number.
        if (holder == null || !this.listMembershipsOf(holder.getProfileId()).isEmpty()
                || this.isAwaitedBack(holder.getProfileId())) {
            return false;
        }
        String released = holder.getLoginMobile();
        holder.setLoginMobile(null);
        identityService.updateLoginMobile(holder.getId(), null);
        UserSecurityLog row = securityLog.entry(SecurityAction.DORMANT_MOBILE_RELEASED,
                SecurityLogSource.SYSTEM, null, holder.getProfileId());
        row.setOldValue(mobileFormat.mask(released));
        row.setReason(scenario);
        securityLog.record(row);
        log.info("Released dormant login mobile from profile {} ({}).", holder.getProfileId(), scenario);
        return true;
    }

    /** Whether a row reopened for this person's re-hire is still waiting for them to join. */
    private boolean isAwaitedBack(Long profileId) {
        return !acrossTenants(() -> this.searchList(new Filters()
                .eq(UserAccount::getFormerProfileId, profileId)
                .isNotSet(UserAccount::getProfileId)
                .in(UserAccount::getStatus, List.copyOf(CONTACTS_EDITABLE)))).isEmpty();
    }

    @Override
    @CrossTenant
    @SkipPermissionCheck
    public boolean isEmailHeldByAnotherPerson(String email, Long profileId, Long exceptAccountId) {
        String wanted = StringUtils.trimToNull(email);
        if (wanted == null) {
            return false;
        }
        // The account being joined or edited never counts against itself: when /join puts the
        // verified person in place of the placeholder minted with the account, the account still
        // names the placeholder until the bind is written.
        return acrossTenants(() -> this.accountsWithEmail(new Filters(), wanted)).stream()
                .filter(account -> account.getStatus() != AccountStatus.DEACTIVATED
                        && !Objects.equals(account.getId(), exceptAccountId))
                .anyMatch(account -> account.getProfileId() != null && !account.getProfileId().equals(profileId));
    }

    @Override
    @CrossTenant
    @SkipPermissionCheck
    @Transactional
    public void followLoginMobile(Long profileId) {
        if (profileId == null) {
            return;
        }
        String loginMobile = workContact(identityService.findByProfile(profileId)
                .map(UserIdentity::getLoginMobile).orElse(null));
        for (UserAccount account : this.listMembershipsOf(profileId)) {
            if (JOINED_STATUSES.contains(account.getStatus())
                    && !Objects.equals(loginMobile, account.getMobile())) {
                // The whole loaded row, written null-preserving: a person with no login mobile left
                // shows none.
                account.setMobile(loginMobile);
                acrossTenants(() -> this.updateOne(account, false));
            }
        }
    }

    /** A status change, in the security log. */
    private void logStatusChange(UserAccount account, AccountStatus from, AccountStatus to,
                                 SecurityLogSource source, String reason) {
        SecurityAction action = to == AccountStatus.FROZEN ? SecurityAction.FROZEN
                : from == AccountStatus.FROZEN && to == AccountStatus.ACTIVE ? SecurityAction.UNFROZEN
                : SecurityAction.STATUS_CHANGED;
        UserSecurityLog row = securityLog.entry(action, source, account, null);
        row.setOldValue(from == null ? null : from.name());
        row.setNewValue(to.name());
        row.setReason(reason);
        securityLog.record(row);
    }

    /** Reset User reminder: the login mobile was replaced, the old number no longer signs in. */
    static final String TEMPLATE_RESET_USER = "user.reset-user";
    /** The enterprise email of a joined account changed. */
    static final String TEMPLATE_EMAIL_CHANGED = "user.enterprise-email-changed";

    /**
     * Reset User: replace the PERSON's login mobile with the personal mobile on
     * the employee record, for someone whose number changed and who can no longer get a code.
     *
     * <p>The login mobile is the person's, so the change holds in every company, and the old number
     * stops signing in anywhere at once. The person stays the same person: the account binding, the
     * enterprise email, the password, the status and the other companies' records are untouched.
     */
    @SkipPermissionCheck
    @CrossTenant
    @Override
    @Transactional
    public void resetWorkContacts(Long userId, String reason) {
        Assert.notNull(userId, "userId is required");
        Assert.notBlank(reason, "A reason is required: record how the employee's identity was verified.");
        UserAccount account = this.getById(userId)
                .orElseThrow(() -> new BusinessException("User not found."));
        if (!JOINED_STATUSES.contains(account.getStatus()) || account.getProfileId() == null) {
            throw new BusinessException("Only an account the employee has joined can be reset.");
        }
        // Read from the employee record, never from the caller: the value cannot be whatever a
        // client chose to post, and it is the record HR has just corrected.
        String newMobile = LoginIdentifiers.normalize(this.archivePersonalMobile(userId));
        if (newMobile == null) {
            throw new BusinessException("This employee's record has no personal mobile — add it there first, then reset.");
        }
        UserIdentity identity = identityService.requireIdentity(account);
        String oldMobile = identity.getLoginMobile();
        if (newMobile.equals(LoginIdentifiers.normalize(oldMobile))) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.MOBILE_UNCHANGED,
                    "No change to the login mobile. Update the employee profile first.");
        }
        // The number may already be someone's login. A dormant someone (nothing left but closed
        // accounts — a number the carrier recycled) gives it up; anyone else keeps it.
        identityService.findByLoginIdentifier(newMobile)
                .filter(other -> !Objects.equals(other.getProfileId(), account.getProfileId()))
                .ifPresent(other -> {
                    if (!this.releaseDormantLoginMobile(newMobile, "Reset User")) {
                        throw AccountErrorCodes.refusal(AccountErrorCodes.MOBILE_TAKEN,
                                "This mobile number is already linked to another account.");
                    }
                });

        identity.setLoginMobile(newMobile);
        identityService.updateLoginMobile(identity.getId(), newMobile);
        this.followLoginMobile(account.getProfileId());
        // Every device of the person, in every company: whoever holds the old number may be signed
        // in somewhere. And no device keeps receiving their notifications.
        sessionService.revokePerson(account.getProfileId(), null, SessionRevokeReason.RESET_USER);
        if (pushDeviceRegistry != null) {
            pushDeviceRegistry.unregisterPerson(account.getProfileId());
        }

        // Tell the OLD number (it may be lost, but if not, the person learns of it) and the
        // enterprise email; a failed send does not undo the reset, it is recorded with it.
        Long tenantId = account.getTenantId();
        Map<String, Object> vars = Map.of(
                "employeeName", StringUtils.defaultString(account.getNickname()),
                "time", LocalDateTime.now().toString());
        List<String> channels = new ArrayList<>();
        List<String> results = new ArrayList<>();
        if (StringUtils.isNotBlank(oldMobile)) {
            channels.add("sms");
            results.add("sms:" + this.remind(() -> eventPublisher.publishEvent(new SmsRequestMessage(
                    List.of(oldMobile), TEMPLATE_RESET_USER, vars))));
        }
        if (StringUtils.isNotBlank(account.getEmail())) {
            channels.add("email");
            results.add("email:" + this.remind(() -> eventPublisher.publishEvent(new MailRequestMessage(
                    List.of(account.getEmail()), TEMPLATE_RESET_USER, vars, tenantId, MessageScope.TENANT))));
        }
        UserSecurityLog row = securityLog.entry(SecurityAction.RESET_USER, SecurityLogSource.USER_ACCOUNTS, account, null);
        row.setReason(reason.trim());
        row.setOldValue(mobileFormat.mask(oldMobile));
        row.setNewValue(mobileFormat.mask(newMobile));
        row.setReminderChannels(String.join(",", channels));
        row.setReminderResult(String.join(",", results));
        securityLog.record(row);
        log.info("Login mobile of the person behind account {} reset. Reason: {}", userId, reason);
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public String enterpriseEmailRefusal(Long accountId, String email) {
        String wanted = workContact(email);
        UserAccount account = accountId == null ? null : this.getById(accountId).orElse(null);
        // A closed account takes no enterprise email (syncEnterpriseEmail leaves it alone), so there
        // is nothing to refuse until a re-hire reopens it — and that is checked then.
        if (wanted == null || account == null || account.getStatus() == AccountStatus.DEACTIVATED) {
            return null;
        }
        if (this.contactHolderInTenant(account.getTenantId(), wanted, accountId).isPresent()) {
            return "That work email already belongs to another account in this company.";
        }
        if (this.isEmailHeldByAnotherPerson(wanted, account.getProfileId(), accountId)) {
            return "That work email is already linked to another person's account.";
        }
        return null;
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    @Transactional
    public void syncEnterpriseEmail(Long accountId, String email) {
        UserAccount account = this.getById(accountId).orElse(null);
        String wanted = workContact(email);
        // A closed account carries no enterprise email: closing released the address for the next
        // hire, and writing it back would block them. A re-hire takes it again when the row reopens.
        if (account == null || account.getStatus() == AccountStatus.DEACTIVATED
                || Objects.equals(StringUtils.lowerCase(wanted), StringUtils.lowerCase(account.getEmail()))) {
            return;
        }
        String refusal = this.enterpriseEmailRefusal(accountId, wanted);
        if (refusal != null) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.EMAIL_TAKEN, refusal);
        }
        String previous = account.getEmail();
        account.setEmail(wanted);
        this.updateOne(account, false);
        // Before the person has joined nobody signs in with it yet: nothing to warn about.
        if (!JOINED_STATUSES.contains(account.getStatus())) {
            return;
        }
        // The old address (whoever holds it should know it no longer signs in) and the person's
        // login mobile; a failed send is recorded, it does not undo the change.
        Map<String, Object> vars = Map.of(
                "employeeName", StringUtils.defaultString(account.getNickname()),
                "time", LocalDateTime.now().toString());
        List<String> channels = new ArrayList<>();
        List<String> results = new ArrayList<>();
        if (StringUtils.isNotBlank(previous)) {
            channels.add("email");
            results.add("email:" + this.remind(() -> eventPublisher.publishEvent(new MailRequestMessage(
                    List.of(previous), TEMPLATE_EMAIL_CHANGED, vars, account.getTenantId(), MessageScope.TENANT))));
        }
        String loginMobile = identityService.findByProfile(account.getProfileId())
                .map(UserIdentity::getLoginMobile).orElse(null);
        if (StringUtils.isNotBlank(loginMobile)) {
            channels.add("sms");
            results.add("sms:" + this.remind(() -> eventPublisher.publishEvent(new SmsRequestMessage(
                    List.of(loginMobile), TEMPLATE_EMAIL_CHANGED, vars))));
        }
        UserSecurityLog row = securityLog.entry(SecurityAction.ENTERPRISE_EMAIL_CHANGED,
                SecurityLogSource.EMPLOYEE_PROFILE, account, null);
        row.setOldValue(ContactMasking.email(previous));
        row.setNewValue(ContactMasking.email(wanted));
        row.setReminderChannels(String.join(",", channels));
        row.setReminderResult(String.join(",", results));
        securityLog.record(row);
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    @Transactional
    public void syncInviteMobile(Long accountId, String mobile) {
        UserAccount account = accountId == null ? null : this.getById(accountId).orElse(null);
        String wanted = workContact(mobile);
        if (account == null || !CONTACTS_EDITABLE.contains(account.getStatus())
                || Objects.equals(LoginIdentifiers.normalize(wanted), LoginIdentifiers.normalize(account.getMobile()))) {
            return;
        }
        account.setMobile(wanted);
        this.updateOne(account, false);
        // The placeholder person minted with the account holds the invitation mobile as its login
        // mobile. Left on the old number it would keep claiming a number the employee no longer has —
        // and, when that number has moved on, block its real owner. It follows the record instead,
        // or lets go when the new number already signs somebody else in.
        Long placeholder = account.getProfileId();
        if (placeholder != null && invitationService.isPlaceholderPerson(placeholder, accountId)) {
            identityService.findByProfile(placeholder).ifPresent(identity -> {
                String next = LoginIdentifiers.normalize(wanted);
                boolean taken = next != null && identityService.findByLoginIdentifier(next)
                        .filter(other -> !Objects.equals(other.getProfileId(), placeholder)).isPresent();
                identity.setLoginMobile(taken ? null : next);
                identityService.updateLoginMobile(identity.getId(), identity.getLoginMobile());
            });
        }
    }

    /** Hand a reminder off for delivery; "sent" or "failed". Never fails the change it reports on. */
    private String remind(Runnable send) {
        try {
            send.run();
            return "sent";
        } catch (RuntimeException e) {
            log.warn("Reminder could not be handed off: {}", e.getMessage());
            return "failed";
        }
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

    @SkipPermissionCheck
    @Override
    public WorkContacts archiveContactsForAccount(Long userId) {
        String loginMobile = userId == null ? null : this.getById(userId)
                .map(UserAccount::getProfileId)
                .flatMap(identityService::findByProfile)
                .map(UserIdentity::getLoginMobile)
                .orElse(null);
        return this.archiveWorkContacts(userId).withMobiles(this.archivePersonalMobile(userId), loginMobile);
    }

    @SkipPermissionCheck
    @Override
    public String archivePersonalMobile(Long userId) {
        if (userId == null || !ModelManager.existModel(ARCHIVE_MODEL) || !ModelManager.existModel(ARCHIVE_PROFILE_MODEL)) {
            return null;
        }
        // Past row scope for the reason archiveWorkContacts gives: the caller is already operating on
        // this account, and this resolves its own record rather than searching.
        Context ctx = ContextHolder.existContext() ? ContextHolder.getContext() : null;
        boolean previous = ctx != null && ctx.isSkipPermissionCheck();
        if (ctx != null) {
            ctx.setSkipPermissionCheck(true);
        }
        try {
            Object profileId = modelService.searchOne(ARCHIVE_MODEL, new FlexQuery(
                            List.of("id", ARCHIVE_PROFILE_FIELD), new Filters().eq(ARCHIVE_USER_FIELD, userId)))
                    .map(row -> row.get(ARCHIVE_PROFILE_FIELD)).orElse(null);
            if (profileId == null) {
                return null;
            }
            Object id = profileId instanceof Map<?, ?> ref ? ref.get("id") : profileId;
            return modelService.searchOne(ARCHIVE_PROFILE_MODEL, new FlexQuery(
                            List.of("id", ARCHIVE_PERSONAL_MOBILE_FIELD), new Filters().eq("id", id)))
                    .map(row -> StringUtils.trimToNull(asString(row.get(ARCHIVE_PERSONAL_MOBILE_FIELD))))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Employee profile read failed while resolving the personal mobile of account {}", userId, e);
            return null;
        } finally {
            if (ctx != null) {
                ctx.setSkipPermissionCheck(previous);
            }
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Optional<UserAccount> contactHolder(Long tenantId, SFunction<UserAccount, String> field, List<String> spellings) {
        Filters inTenant = new Filters().eq(UserAccount::getTenantId, tenantId);
        if (spellings.size() == 1 && spellings.getFirst().contains("@")) {
            // An email, compared case aside: a plain equality is case-sensitive on PostgreSQL, and
            // "Ada@acme.com" and "ada@acme.com" are one mailbox.
            String email = spellings.getFirst();
            return this.searchList(inTenant.startWith(field, likeLiteral(email))).stream()
                    .filter(account -> email.equalsIgnoreCase(StringUtils.trim(field.apply(account))))
                    .findFirst();
        }
        return this.searchList(contactFilter(field, spellings).and(inTenant)).stream().findFirst();
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

    /**
     * Whether a work contact and a login identifier name the same address.
     *
     * <p>Compared in LoginIdentifiers' canonical form on BOTH sides: the contact is stored as HR
     * typed it, the identifier as the seeding wrote it, and a comparison that trusted either
     * spelling would leave an identifier this company issued in place when the contact moves or the
     * membership closes — a live login route into an address about to be handed to someone else.
     */
    /** The account fields that are also login identifiers when they are the company's to issue. */
    private static final String EMAIL_FIELD = "email";
    private static final String MOBILE_FIELD = "mobile";

    /** Contacts an account form may still edit: nobody has accepted the membership yet. */
    private static final Set<AccountStatus> CONTACTS_EDITABLE = EnumSet.of(AccountStatus.PENDING, AccountStatus.INVITED);

    static final String RESET_INSTEAD = "This account is already in use. Change its work email or "
            + "mobile with Reset User, which also tells the old address.";

    @Override
    @Transactional
    public <T> T updateCarryingLoginIdentifiers(List<Map<String, Object>> rows, Supplier<T> write) {
        for (Map<String, Object> row : rows) {
            if (row.containsKey(EMAIL_FIELD) || row.containsKey(MOBILE_FIELD)) {
                this.carryLoginIdentifiers(row);
            }
        }
        return write.get();
    }

    /** One row's share of {@link #updateCarryingLoginIdentifiers}: refuse, or move what changed. */
    private void carryLoginIdentifiers(Map<String, Object> row) {
        Long id = row.get("id") instanceof Number number ? number.longValue() : null;
        if (id == null) {
            return;     // the write itself rejects a row without an id
        }
        UserAccount account = this.getById(id).orElse(null);
        if (account == null) {
            return;
        }
        boolean emailChanged = row.containsKey(EMAIL_FIELD)
                && !sameContact(account.getEmail(), (String) row.get(EMAIL_FIELD));
        boolean mobileChanged = row.containsKey(MOBILE_FIELD)
                && !sameContact(account.getMobile(), (String) row.get(MOBILE_FIELD));
        if (!emailChanged && !mobileChanged) {
            return;
        }
        if (!CONTACTS_EDITABLE.contains(account.getStatus())) {
            throw new BusinessException(RESET_INSTEAD);
        }
        // Only the mobile has a copy on the person to keep in step. An email signs in through the
        // joined account that carries it, so the account's own email is the whole of it; the
        // identity's login email is read for consultants alone, whose address is never a company's.
        if (account.getProfileId() == null || !mobileChanged) {
            return;     // no person yet, or nothing they sign in with has changed
        }
        // Only the placeholder minted with the account is the company's to renumber. A real person's
        // login mobile is theirs, in every company: an account form must not move it, silently, to
        // whatever number HR typed — that would sign them in, everywhere, wherever the code lands.
        if (!invitationService.isPlaceholderPerson(account.getProfileId(), account.getId())) {
            return;
        }
        identityService.findByProfile(account.getProfileId())
                .filter(identity -> isSameLoginIdentifier(account.getMobile(), identity.getLoginMobile()))
                .ifPresent(identity -> {
                    identity.setLoginMobile(this.claimableBy(identity, (String) row.get(MOBILE_FIELD)));
                    identityService.updateLoginMobile(identity.getId(), identity.getLoginMobile());
                });
    }

    /**
     * The canonical identifier for {@code contact}, refused when it already signs in someone else —
     * one identifier naming two people would let a code sent there open either.
     */
    private String claimableBy(UserIdentity identity, String mobile) {
        String identifier = LoginIdentifiers.normalize(mobile);
        if (identifier != null) {
            identityService.findByLoginIdentifier(identifier)
                    .filter(other -> !Objects.equals(other.getProfileId(), identity.getProfileId()))
                    .ifPresent(other -> {
                        throw new BusinessException("That mobile already signs in another person.");
                    });
        }
        return identifier;
    }

    private static boolean sameContact(String stored, String incoming) {
        return Objects.equals(LoginIdentifiers.normalize(stored), LoginIdentifiers.normalize(incoming));
    }

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
        // And across companies: an enterprise email is one person's everywhere (closed accounts
        // aside). The person coming back is the one this row belonged to.
        Long returning = account.getProfileId() != null ? account.getProfileId() : account.getFormerProfileId();
        if (this.isEmailHeldByAnotherPerson(workEmail, returning, account.getId())) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.EMAIL_TAKEN,
                    "That work email is already linked to another person's account.");
        }
        Long previousPerson = account.getProfileId();
        reviveWith(account, workEmail, workMobile);
        // Nobody is behind the reopened row until /join: whatever session still names it is ended.
        sessionService.revokeAccount(account.getId(), SessionRevokeReason.UNBOUND);
        this.logStatusChange(account, AccountStatus.DEACTIVATED, AccountStatus.PENDING,
                SecurityLogSource.EMPLOYEE_PROFILE, null);
        // updateOne(entity, false): reviveWith clears activationTime to null, which the default
        // overload drops — the revived PENDING row would otherwise keep the previous stint's
        // activation timestamp.
        this.updateOne(account, false);
        log.info("Revived membership {} (was profile {}) — reset to PENDING, awaiting a new invitation.",
                account.getId(), previousPerson != null ? previousPerson : account.getFormerProfileId());
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
        account.setClosedEmail(null);
        account.setMobile(workContact(workMobile));
        account.setStatus(AccountStatus.PENDING);
        account.setActivationTime(null);
        // The person is remembered, then unbound until /join. Who joins is decided by the number
        // verified there: the remembered person holding it is recognised and bound back, keeping
        // their password and settings; anyone else holding the old number cannot walk into the
        // leaver's person and their other companies. A row reopened again before anyone joined keeps
        // the person it already remembers.
        if (account.getProfileId() != null) {
            account.setFormerProfileId(account.getProfileId());
        }
        account.setProfileId(null);
        account.setNickname(null);
        account.setUsername(null);
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
        // Strength is enforced inside identityService.setPassword, the choke point every
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
        // Every other device of the person is signed out — in every company; this one stays.
        sessionService.revokePerson(user.getProfileId(), sessionService.currentSessionId(),
                SessionRevokeReason.KICKED_BY_OTHER_DEVICE);
        securityLog.record(securityLog.entry(SecurityAction.PASSWORD_CHANGED,
                ClientInfo.current().isApp() ? SecurityLogSource.APP : SecurityLogSource.WEB, user, null));
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
        // has one answer, and LoginService owns it — a consultant is exempt, a person with
        // no credentials row at all is not forced into a screen the set-password call would refuse.
        return this.getById(userId)
                .map(UserAccount::getProfileId)
                .map(loginService::mustSetPassword)
                .orElse(false);
    }

    @Override
    public boolean canSetMyFirstPassword() {
        Long userId = ContextHolder.getContext().getUserId();
        if (userId == null) {
            return false;
        }
        // The precondition setMyFirstPassword enforces, asked ahead of time so a screen can offer
        // the right form. Deliberately NOT mustSetMyPassword: that one is about being forced, and
        // it exempts consultants — who are exactly the people with no password and no other way to
        // get one. The lenient identity lookup, because "no credentials row" is a false here rather
        // than an error: nothing is being done, only answered.
        return this.getById(userId)
                .map(UserAccount::getProfileId)
                .flatMap(identityService::findByProfile)
                .map(identity -> StringUtils.isBlank(identity.getPassword()))
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
        // Strength is enforced inside identityService.setPassword — see changeMyPassword.
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
        // Clear the password lock on the way through. Leaving it would mean that lifting the freeze hands back an account the lockout
        // still refuses, and the administrator who lifted it cannot see why.
        identityService.findByProfile(user.getProfileId()).ifPresent(identity -> {
            if (identity.getPasswordLockedUntil() != null) {
                identity.setPasswordLockedUntil(null);
                identityService.clearPasswordLock(identity.getId());
                loginHistory.record(LoginEvent.UNLOCKED, user.getProfileId(), user.getId(), user.getTenantId(),
                        null, null, null);
            }
            identityService.clearPasswordFailures(identity.getId());
        });
        AccountStatus previous = user.getStatus();
        user.setStatus(AccountStatus.FROZEN);
        this.updateOne(user);
        this.logStatusChange(user, previous, AccountStatus.FROZEN, SecurityLogSource.USER_ACCOUNTS, reason);
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
        this.logStatusChange(user, AccountStatus.FROZEN, AccountStatus.ACTIVE, SecurityLogSource.USER_ACCOUNTS, reason);
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