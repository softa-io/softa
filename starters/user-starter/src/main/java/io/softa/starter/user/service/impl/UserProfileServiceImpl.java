package io.softa.starter.user.service.impl;

import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.constant.StringConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BaseException;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.LambdaUtils;
import org.springframework.transaction.annotation.Transactional;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.FileService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.framework.orm.service.UserInfoService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserProfile;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;
import io.softa.starter.user.util.LoginIdentifiers;

/**
 * UserProfile Model Service Implementation
 */
@Slf4j
@Service
public class UserProfileServiceImpl extends EntityServiceImpl<UserProfile, Long>
        implements UserProfileService, UserInfoService {

    @Autowired
    private FileService fileService;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private TenantInfoService tenantInfoService;

    /** @Lazy breaks a potential UserAccount ⇄ UserProfile service cycle; used only to
     *  read the account's status when (re)building the cached UserInfo. */
    @Autowired
    @Lazy
    private UserAccountService accountService;

    /** Credentials live on UserIdentity, a 1:1 satellite created alongside the profile at
     *  registration; this creates and seeds that row. */
    @Autowired
    private UserIdentityService identityService;

    /**
     * Get Current User Profile
     *
     * <p><b>Why {@code @SkipPermissionCheck}</b>: the filter below pins the row to
     * {@code Context.getUserId()}, so the only thing row scope can still do is take that one row
     * away. {@code UserProfile} is anchorless — a person has no department, no employee, nothing a
     * scope rule can reach it through — so a role with no explicit rule on it fails closed to
     * {@code matchNone()} and the caller cannot read their own profile. What authorizes this call is
     * that the caller is authenticated, which is already asserted: {@code /UserProfile/getMy*} is
     * listed in {@code permission.authenticated-bypass-patterns}, declaring these endpoints open to
     * every logged-in user. That declaration only opened the endpoint gate; this closes the same
     * question at the data layer, where it was still being answered "no".
     *
     * <p>The waiver is safe because it cannot be widened by input: the id comes from the request
     * context, never from a parameter. Contrast {@link #getUserInfo(Long)}, which takes the id as an
     * argument and therefore stays checked — see {@link #getMyUserInfo()}.
     *
     * <p>Note this is also the fetch step of {@link #saveMyProfile}, which carries its own waiver —
     * the flag does not survive this method's return, so the write path cannot borrow this one.
     */
    /**
     * The id of the PERSON behind this session, resolved through their membership.
     *
     * <p>{@code Context} carries the account id — which membership is being used right now — and a
     * person may hold several. The profile is one row for the person, keyed by its own id, which is
     * what {@code UserAccount.profileId} points at.
     *
     * <p>Both reads below used to filter on {@code UserProfile.userId} instead. That column is a
     * leftover from the 1:1 era and says so on the field: "kept only so the data migration can map
     * the old pairing". It can only name ONE of a person's memberships, so signing in through any
     * other one made their own profile invisible — the dialog answered "Current user profile not
     * found" while the row sat in the table. A consultant meets this every time, because they are
     * created by the platform and then granted into a customer's tenant, so the membership they use
     * is never the one the back-pointer happens to name. The eviction below already reasons this way
     * and says why; the fetch did not.
     */
    private Long requireCurrentProfileId() {
        Long userId = ContextHolder.getContext().getUserId();
        return accountService.getById(userId)
                .map(UserAccount::getProfileId)
                // Refused here rather than filtered on: an id this method could not resolve must
                // never reach the query as a null, because what a null does to an equality filter
                // is the ORM's business and "matches everything" would hand back somebody else's
                // profile. Same message either way — from the caller's side the profile is missing.
                .orElseThrow(() -> new IllegalArgumentException("Current user profile not found."));
    }

    @SkipPermissionCheck
    @Override
    public UserProfile getCurrentUserProfile() {
        Filters profileFilters = new Filters().eq(UserProfile::getId, requireCurrentProfileId());
        Optional<UserProfile> profileOpt = this.searchOne(profileFilters);
        return profileOpt.orElseThrow(() -> new IllegalArgumentException("Current user profile not found."));
    }

    /**
     * Get Current User Profile as Map
     *
     * <p>Waived for the same reason as {@link #getCurrentUserProfile()}, and with the same bound:
     * the filter is built from {@code Context.getUserId()}. Field masking goes with the row check,
     * which is correct here — masking a person's own details from themselves has no reader to
     * protect, and there is nothing secret left on this row anyway (see the note below).
     */
    @SkipPermissionCheck
    @Override
    public Map<String, Object> getCurrentUserProfileMap() {
        Filters profileFilters = new Filters().eq(UserProfile::getId, requireCurrentProfileId());
        FlexQuery flexQuery = new FlexQuery(profileFilters);
        flexQuery.setConvertType(ConvertType.REFERENCE);
        Optional<Map<String, Object>> profileOpt = this.modelService.searchOne(this.modelName, flexQuery);
        Map<String, Object> profile = profileOpt
                .orElseThrow(() -> new IllegalArgumentException("Current user profile not found."));
        // No credential fields to strip any more: the password hash, salt and login identifiers moved
        // to UserIdentity. A profile map is now just the person's own display information.
        return profile;
    }

    /**
     * <p><b>Why {@code @SkipPermissionCheck}, and why the write moved here</b>: the waiver aspect
     * restores the flag when the annotated method returns, so a controller that fetched through the
     * waived {@link #getCurrentUserProfile()} and then called a bare {@code updateOne} had only half
     * its work covered — the fetch succeeded and the save failed closed, on the same anchorless
     * model, for the same caller-pinned row. One service method makes the read-modify-write a single
     * waived span.
     *
     * <p>The waiver is bounded on both sides: the row is fetched by {@code Context.getUserId()}
     * (never a parameter), and the field-copy below is the write whitelist — the DTO carries only
     * the person's own display fields, so no tenant, membership or credential value can arrive
     * through this endpoint however the payload is crafted. Credentials live on {@code UserIdentity}
     * and are changed via {@code changeMyPassword}, which verifies the current password first.
     */
    @SkipPermissionCheck
    @Override
    public void saveMyProfile(UserProfileDTO myProfileDTO) {
        UserProfile profile = getCurrentUserProfile();
        profile.setFullName(myProfileDTO.getFullName());
        profile.setChineseName(myProfileDTO.getChineseName());
        profile.setBirthDate(myProfileDTO.getBirthDate());
        profile.setBirthTime(myProfileDTO.getBirthTime());
        profile.setBirthCity(myProfileDTO.getBirthCity());
        profile.setGender(myProfileDTO.getGender());
        profile.setPhotoId(myProfileDTO.getPhotoId());
        profile.setLanguage(myProfileDTO.getLanguage());
        profile.setTimezone(myProfileDTO.getTimezone());
        // updateOne(profile, false) — nulls overwrite. The one-arg overload drops null keys before
        // they reach the update (BeanTool.objectToMap(entity, true)), which is the right default when
        // an entity is only partially populated: a Java object cannot tell "not supplied" from
        // "clear this". Here it is wrong, because the entity above IS fully populated — every column
        // is either the value just read or the value the caller sent — so a null can only mean the
        // caller cleared the field. With the default, clearing silently does nothing: the avatar the
        // helper text says appears "in the workspace header, comments, approvals, and people
        // directories" cannot be removed, and the optional birth details cannot be taken back. Free
        // text escaped it only by accident — the form sends "" for a cleared string, and "" is not
        // null. Safe because the fetch selects every column: the three fields the DTO does not carry
        // (id, userId, density) are written back exactly as read.
        this.updateOne(profile, false);
        // The cached UserInfo carries name / language / timezone / photo — all editable here.
        // Evict EVERY membership's entry, not profile.getUserId(): the person is one, the cache is
        // keyed per account, and that legacy back-pointer names only one of them. Missing the rest
        // leaves the other tenants serving the old name and avatar until the entry expires a
        // month later — for a person who has just been told the change was saved.
        accountService.listMembershipsOf(profile.getId())
                .forEach(account -> this.evictUserInfo(account.getId()));
    }

    /**
     * <p><b>Why {@code @SkipPermissionCheck}</b>: same reasoning as {@link #getCurrentUserProfile()}
     * — the id comes from the request context, and the lookup this delegates to reads the caller's
     * own {@code UserProfile}, which is anchorless and therefore fails closed without an explicit
     * rule. This one matters most: {@code getUserInfo} sits on the login path, and its result is
     * cached for a month, so the failure is delayed rather than immediate. A session established
     * while the cache was warm keeps working and only the uncached reads (the personal-settings
     * dialog) fail — until the entry expires, at which point login itself starts refusing the user.
     */
    @SkipPermissionCheck
    @Override
    public UserInfo getMyUserInfo() {
        return this.getUserInfo(ContextHolder.getContext().getUserId());
    }

    /**
     * Get UserInfo from cache or database.
     *
     * <p>Deliberately NOT waived: {@code userId} is a parameter. Self-service callers go through
     * {@link #getMyUserInfo()}; the authenticated paths that legitimately pass another id
     * (login, OAuth callback) run before a permission snapshot exists, which
     * {@code PermissionServiceImpl} already treats as a bypass.
     */
    @Override
    public UserInfo getUserInfo(Long userId) {
        // Check and potentially update UserInfo cache
        String userInfoKey = RedisConstant.USER_INFO + userId;
        UserInfo userInfo = cacheService.get(userInfoKey, UserInfo.class);
        if (userInfo == null) {
            // Resolve the person FROM the membership, never the reverse. UserProfile.userId is a
            // single-valued back-pointer left over from the 1:1 era: it names one account, so a
            // person with two memberships is reachable through exactly one of them and every other
            // company reports "profile not found" on the way in. Reading through account.profileId
            // is the direction that survives N accounts per person.
            UserAccount account = accountService.getById(userId).orElseThrow(
                    () -> new BusinessException("User account not found for user ID: " + userId));
            UserProfile profile = Optional.ofNullable(account.getProfileId())
                    .flatMap(this::getById)
                    .orElseThrow(() -> new BusinessException(
                            "User profile not found for user ID: " + userId));
            userInfo = this.buildUserInfo(profile, account);
            this.refreshUserInfo(userId, userInfo);
        }
        return userInfo;
    }

    /**
     * The framework-facing half of {@link #getUserInfo(Long)}: same cache-or-database load, but a
     * missing account / profile answers {@code null} instead of throwing.
     *
     * <p>{@code ContextBuilder} calls this on every request whose {@code user-info:} entry is cold,
     * which is any request following one of this codebase's evictions. It runs before a context
     * exists, exactly as the tenant gate beside it does, so an exception escaping here would surface
     * as a server error on what is really "this session cannot be resolved" — a condition the caller
     * already has a correct answer for.
     *
     * <p><b>Why the explicit cross-tenant context</b>: {@code UserAccount} is {@code multiTenant},
     * and the tenant this read would be filtered by is the one thing it is trying to find out. There
     * is no context bound at all this early — {@code ContextHolder} hands back an empty one — so the
     * filter degrades to {@code tenant_id = NULL}, matches nothing, and the rebuild would report
     * every session as a missing user while looking like it worked. The read is by primary key, and
     * the key came from {@code session:{sessionId}} — the server's own record of whose session this
     * is — so the cross-tenant lookup is as narrow as it gets. Same shape as corehr's
     * {@code EmployeeEventBridge.resolveUserHandle}.
     *
     * <p>Set in code rather than with {@code @CrossTenant}: that annotation is Spring-AOP advice and
     * only fires on calls arriving through the bean proxy, and {@link #getUserInfo(Long)} is reached
     * here by self-invocation.
     *
     * <p>{@link BaseException} and not {@code BusinessException}: the build fails two ways, and they
     * are siblings rather than one hierarchy. A missing account or profile raises
     * {@code BusinessException}; the tenant checks inside {@code buildUserInfo} go through
     * {@code Assert}, which raises {@code IllegalArgumentException}. Both mean the same thing here.
     */
    @Override
    public UserInfo loadUserInfo(Long userId) {
        if (userId == null) {
            return null;
        }
        Context ctx = ContextHolder.cloneContext();
        ctx.setCrossTenant(true);
        ctx.setSkipPermissionCheck(true);
        try {
            return ContextHolder.callWith(ctx, () -> this.getUserInfo(userId));
        } catch (BaseException e) {
            log.warn("loadUserInfo — cannot build UserInfo for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    @Override
    public void evictUserInfo(Long userId) {
        if (userId == null) {
            return;
        }
        cacheService.clear(RedisConstant.USER_INFO + userId);
    }

    /**
     * Refresh UserInfo cache
     *
     * @param userId User ID
     * @param userInfo UserInfo object
     */
    private void refreshUserInfo(Long userId, UserInfo userInfo) {
        String userInfoKey = RedisConstant.USER_INFO + userId;
        cacheService.save(userInfoKey, userInfo, RedisConstant.ONE_MONTH);
    }

    /**
    * Build UserInfo from UserProfile and save to cache
    *
    * @param profile UserProfile
    * @return UserInfo object
    */
    /**
     * @param account the membership this session is being established in — NOT looked up from the
     *                person. A person may hold several, and the whole point of the company picker
     *                is that the caller has already chosen one. Deriving it here from
     *                {@code profile.getUserId()} would silently pin every session to whichever
     *                account that legacy back-pointer happens to name: pick the second company, get
     *                a session in the first, with the wrong tenantId and no error anywhere.
     */
    private UserInfo buildUserInfo(UserProfile profile, UserAccount account) {
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(account.getId());
        userInfo.setName(profile.getFullName());
        userInfo.setLanguage(profile.getLanguage());
        userInfo.setTimezone(profile.getTimezone());
        // The tenant comes from the MEMBERSHIP, not the person: a person is global, and which
        // company this session is in is exactly what the chosen account represents.
        userInfo.setTenantId(account.getTenantId());
        // Reflect the account's live status so ContextBuilder can force-logout a frozen account.
        // LOCKED is legacy data (the lock lives on the person now) and enters like ACTIVE at sign-in;
        // reading it as inactive here would end every session it is given, on its first request.
        userInfo.setActive(AccountStatus.ACTIVE == account.getStatus() || AccountStatus.LOCKED == account.getStatus());
        this.validateTenantInfo(profile, userInfo.getTenantId());
        if (profile.getPhotoId() != null) {
            // The photo URL expires in one quarter (90 days), longer than the user info cache expiration time
            Optional<FileInfo> fileInfoOpt = fileService.getByFileId(profile.getPhotoId(), RedisConstant.ONE_QUARTER);
            fileInfoOpt.ifPresent(fileInfo -> userInfo.setPhotoUrl(fileInfo.getUrl()));
        }
        return userInfo;
    }

    private void validateTenantInfo(UserProfile profile, Long tenantId) {
        if (ModelManager.isMultiTenantControl()) {
            Assert.notNull(tenantId,
                    "UserProfile(id = {0}) has no membership to take a tenant from; "
                            + "a person must belong to at least one company to sign in.",
                    profile.getId());
            Assert.notNull(tenantInfoService,
                    "Multi-tenant control is enabled but no TenantInfoService is available; "
                            + "ensure tenant-starter is on the classpath.");
            Assert.isTrue(tenantInfoService.isTenantActive(tenantId),
                    "Tenant with tenantId {0} is not active", tenantId);
        }
    }

    /**
     * Build UserProfile from UserProfileDTO
     *
     * @param profileInfo UserProfileDTO
     * @return UserProfile object
     */
    private UserProfile buildUserProfile(UserProfileDTO profileInfo) {
        Context context = ContextHolder.getContext();
        UserProfile userProfile = new UserProfile();
        userProfile.setFullName(profileInfo.getFullName());
        userProfile.setChineseName(profileInfo.getChineseName());
        userProfile.setGender(profileInfo.getGender());
        userProfile.setBirthDate(profileInfo.getBirthDate());
        userProfile.setBirthTime(profileInfo.getBirthTime());
        userProfile.setBirthCity(profileInfo.getBirthCity());
        userProfile.setPhotoId(profileInfo.getPhotoId());
        userProfile.setLanguage(Optional.ofNullable(profileInfo.getLanguage()).orElse(context.getLanguage()));
        userProfile.setTimezone(Optional.ofNullable(profileInfo.getTimezone()).orElse(context.getTimezone()));
        // No tenant stamped here any more: a person is global, and the tenant is held by the
        // UserAccount that represents their membership.
        return userProfile;
    }

    /**
     * Register new user profile when user register
     *
     * @param userId User ID
     * @param profileDTO User profile DTO
     * @return UserInfo object
     */
    /**
     * <p><b>Why {@code @SkipPermissionCheck}</b>: everything written below is bookkeeping this call
     * mints for itself — the person, the membership's link to them, and their credentials row. Row
     * scope answers "which existing rows may this caller reach", and none of these existed a moment
     * ago, so for them the question is not unanswered but unanswerable: no rule can put an id in
     * scope that the same call is about to generate. Left checked, {@code checkIdsFieldsAccess}
     * fails closed on {@code UserProfile} and {@code UserIdentity} — nothing a role holds references
     * either — so a role that may legitimately create an employee ("create employee" is the granted
     * action) is stopped halfway through provisioning that employee's login.
     *
     * <p>What actually authorized this is the action that led here, checked where it happened: the
     * endpoint gate on {@code /Employee/createOne}, on {@code /UserAccount/create}, on the OAuth
     * callback. This method is never an entry point of its own.
     *
     * <p>The waiver stops at this method. The business entity that caused the provisioning — the
     * Employee row — is created by the caller and keeps its own CREATE scope check, so a role scoped
     * to one department subtree still cannot place an employee outside it. Same reasoning, and same
     * annotation, as {@code UserIdentityServiceImpl.requireIdentity}.
     */
    @SkipPermissionCheck
    @Override
    @Transactional
    public UserInfo registerUserProfile(Long userId, UserProfileDTO profileDTO) {
        // @Transactional on the atomic unit itself, not just its callers: this writes a profile,
        // links the account to it, and creates the credentials row — the three that together make a
        // person able to log in. A partial failure (identity insert throws after the account is
        // linked) leaves exactly the "account has a profileId but no identity" fault the class
        // warns about, unrecoverable and login-breaking. Current callers are transactional, but an
        // atomic unit that relies on every future caller remembering that is one bad merge from the
        // fault. REQUIRED propagation joins an existing transaction, so this changes nothing for them.
        // Create user profile
        UserProfile userProfile = this.buildUserProfile(profileDTO);
        userProfile.setUserId(userId);

        // Loudly, not orElse(null): silently skipping here would return success for a person
        // who can never authenticate — no identity row, and no profileId link below. The one
        // caller that legitimately has no account does not exist; every path creates the
        // account first.
        UserAccount account = accountService.getById(userId).orElseThrow(
                () -> new BusinessException(
                        "Account " + userId + " not found — a profile cannot be registered before its account."));
        Long profileId = this.createOne(userProfile);
        userProfile.setId(profileId);

        // Point the membership at the person. UserAccount.profileId is the relation now
        // (UserProfile.userId is the legacy back-reference kept for the migration); skipping this
        // would leave an account whose credentials cannot be resolved at all — every password path
        // fails with "not linked to a person".
        account.setProfileId(profileId);
        accountService.updateOne(account);

        // Create the person's UserIdentity (1:1 satellite) and seed the LOGIN identifiers from the
        // account's work contacts, in the same transaction — a person is not fully created until
        // their credentials row exists, and requireIdentity resolves through it.
        //
        // Login RESOLVES people by these identifiers now, so seeding them here is what makes a
        // freshly created person able to sign in at all — it is the only place a person created
        // from an account gets one.
        UserIdentity identity = new UserIdentity();
        identity.setProfileId(profileId);
        // Seeded only when the value is not ALREADY someone's login identifier. A work contact and
        // a login identifier are different roles for the same string, and only the second has to be
        // unique: shared work numbers are ordinary (a shop's phone, a shared floor handset, a
        // manager's number entered for a worker who has none). Copying such a number across anyway
        // does not create a login route, it destroys one — both holders then resolve to "shared by
        // more than one account" instead of the one who had it to themselves.
        // The login mobile only: the enterprise email signs the person in through the joined
        // account itself, so no copy of it is kept on the identity.
        identity.setLoginMobile(claimable(account.getMobile(), profileId));
        identityService.createOne(identity);

        // Build UserInfo and upload photo if photo is not empty
        UserInfo userInfo = this.buildUserInfo(userProfile, account);

        // Update UserInfo cache
        this.refreshUserInfo(userId, userInfo);

        return userInfo;
    }

    /**
     * <p><b>Why {@code @SkipPermissionCheck}</b>: same reasoning as {@link #registerUserProfile} —
     * the account being linked was minted by the same call chain, so no row rule can have it in
     * scope yet, and the person is a global model nothing a role holds references. What authorized
     * this is the create action that led here, checked where it happened.
     *
     * <p>{@code @Transactional} for the same reason too: the link and the cache eviction must not
     * half-apply. There is no identity row to write here, which is exactly what makes this the
     * cheaper half — the person already has one.
     */
    @SkipPermissionCheck
    @Override
    @Transactional
    public UserInfo linkAccountToPerson(Long userId, Long profileId) {
        Assert.notNull(userId, "userId is required to link an account to a person.");
        Assert.notNull(profileId, "profileId is required to link an account to a person.");
        UserAccount account = accountService.getById(userId).orElseThrow(
                () -> new BusinessException(
                        "Account " + userId + " not found — it must exist before being linked."));
        UserProfile profile = this.getById(profileId).orElseThrow(
                () -> new BusinessException("Person " + profileId + " not found."));

        // The authoritative pointer, and the only write this needs: UserAccount.profileId IS the
        // relation. UserProfile.userId is the legacy back-pointer and is deliberately left alone —
        // it holds one account and the person now has several, so repointing it at the newest would
        // simply move the breakage to whichever membership it stopped naming.
        account.setProfileId(profileId);
        accountService.updateOne(account);

        UserInfo userInfo = this.buildUserInfo(profile, account);
        this.refreshUserInfo(userId, userInfo);
        return userInfo;
    }

    /** The work contact, or null when it is already someone else's login identifier. */
    private String claimable(String contact, Long profileId) {
        // Stored in LoginIdentifiers' canonical form — the form login looks it up in. The account
        // keeps the contact as typed; only the identifier is normalised.
        String value = LoginIdentifiers.normalize(contact);
        return value != null && identityService.isIdentifierClaimable(value, profileId) ? value : null;
    }

    @SkipPermissionCheck
    @Override
    @Transactional
    public Long createPersonForJoin(String identifier) {
        identifier = LoginIdentifiers.normalize(identifier);
        Assert.notBlank(identifier, "An identifier is required to create a person.");
        // @SkipPermissionCheck for the same reason registerUserProfile carries it: these are rows
        // this method mints itself, and on /join the caller has no session at all. Both models are
        // global, so no @CrossTenant is needed. @Transactional on the unit itself, like
        // registerUserProfile: a profile without its identity row fails every later
        // requireIdentity, and relying on each caller to wrap it is one merge from that fault.
        // REQUIRED propagation joins verifyJoinCode's transaction, so this is a no-op there.
        UserProfile profile = new UserProfile();
        Long profileId = this.createOne(profile);

        UserIdentity identity = new UserIdentity();
        identity.setProfileId(profileId);
        // Which column depends on the channel, and "@" is the only thing that distinguishes them
        // once we hold a bare address. A dial-code mobile can never contain one.
        if (identifier.contains("@")) {
            identity.setLoginEmail(identifier);
        } else {
            identity.setLoginMobile(identifier);
        }
        // No claimable() guard here, deliberately: on /join the identifier is the address the
        // invitation was SENT to and the code was verified against, so this person demonstrably
        // controls it. If another identity holds it, that is the duplicate to resolve — refusing
        // here would instead refuse the person who just proved control.
        identityService.createOne(identity);
        return profileId;
    }

    /**
     * Fetch user photo from remote URL and save it locally
     *
     * @param photoUrl Photo URL
     * @param profileId Profile ID
     * @return FileInfo of the saved photo
     */
    @Override
    public FileInfo fetchPhotoFromURL(String photoUrl, Long profileId) {
        if (StringUtils.isNotBlank(photoUrl) && (
                photoUrl.startsWith(StringConstant.HTTP_PREFIX)
                        || photoUrl.startsWith(StringConstant.HTTPS_PREFIX))) {
            String fieldName = LambdaUtils.getAttributeName(UserProfile::getPhotoId);
            try {
                // The photo URL expires in one quarter (90 days), longer than the user info cache expiration time
                return fileService.uploadFromUrl(this.modelName, profileId, fieldName, photoUrl, RedisConstant.ONE_QUARTER);
            } catch (Exception e) {
                log.error("Failed to upload photo from URL: {}", photoUrl, e);
            }
        }
        return null;
    }

}