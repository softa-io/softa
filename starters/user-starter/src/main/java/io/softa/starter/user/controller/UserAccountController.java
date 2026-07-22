package io.softa.starter.user.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.DataMask;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.dto.QueryParams;
import io.softa.framework.web.dto.SearchListParams;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.utils.CookieUtils;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.dto.ChangePasswordDTO;
import io.softa.starter.user.dto.UnlockAccountDTO;
import io.softa.starter.user.dto.UnlockAccountsDTO;
import io.softa.starter.user.dto.UserAccountDTO;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.service.PermissionCacheInvalidator;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserRoleRelService;

/**
 * UserAccount Controller
 */
@Tag(name = "UserAccount Controller")
@RestController
@RequestMapping("/UserAccount")
public class UserAccountController extends EntityController<UserAccountService, UserAccount, Long> {

    private static final Logger log = LoggerFactory.getLogger(UserAccountController.class);

    private static final String MODEL = "UserAccount";
    private static final String ROLES_FIELD = "roles";

    /** Roles whose holders make up the platform super-admin's account roster. */
    private static final List<String> ADMIN_ROLE_CODES =
            List.of(RoleConstant.CODE_TENANT_ADMIN, RoleConstant.CODE_SUPER_ADMIN);

    @Autowired
    private CacheService cacheService;

    @Autowired
    private ModelService<Long> modelService;

    @Autowired
    private PermissionCacheInvalidator permissionCacheInvalidator;

    @Autowired
    private UserInvitationService invitationService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private UserRoleRelService userRoleRelService;

    /**
     * Create a UserAccount from the standard create form. Routes through
     * {@link #inviteFromRow} so the account is provisioned as INVITED and paired with a UserProfile
     * (a bare generic create would leave it profile-less → login fails). Spring routes here over the
     * templated {@code /{modelName}/createOne} (literal path is more specific).
     */
    @Operation(summary = "Create a UserAccount — provisions an INVITED account paired with a UserProfile")
    @PostMapping("/createOne")
    @DataMask
    @Transactional
    public ApiResponse<Long> createOne(@RequestBody Map<String, Object> row) {
        return ApiResponse.success(inviteFromRow(row));
    }

    @Operation(summary = "Create a UserAccount and fetch — provisions an INVITED account paired with a UserProfile")
    @PostMapping("/createOneAndFetch")
    @DataMask
    @Transactional
    public ApiResponse<Map<String, Object>> createOneAndFetch(@RequestBody Map<String, Object> row) {
        return ApiResponse.success(fetchRef(inviteFromRow(row)));
    }

    @Operation(summary = "Create UserAccounts — each provisioned as an INVITED account paired with a UserProfile")
    @PostMapping("/createList")
    @Transactional
    public ApiResponse<List<Long>> createList(@RequestBody List<Map<String, Object>> rows) {
        validateBatchSize(rows.size());
        return ApiResponse.success(rows.stream().map(this::inviteFromRow).toList());
    }

    @Operation(summary = "Create UserAccounts and fetch — each provisioned as an INVITED account paired with a UserProfile")
    @PostMapping("/createListAndFetch")
    @DataMask
    @Transactional
    public ApiResponse<List<Map<String, Object>>> createListAndFetch(@RequestBody List<Map<String, Object>> rows) {
        validateBatchSize(rows.size());
        return ApiResponse.success(rows.stream().map(this::inviteFromRow).map(this::fetchRef).toList());
    }

    /**
     * Provision a UserAccount create payload as an INVITED account paired with a UserProfile, rather
     * than a bare row insert. A generic {@code createOne(UserAccount)} builds only the account and
     * leaves it with no UserProfile — login's {@code getUserInfo} then throws "User profile not
     * found" and the account can never sign in. Routing every HTTP create through
     * {@link UserAccountService#registerInvitedUser} makes "create a user" mean "create an invited
     * user (+ profile)"; an admin then sends the set-password mail via the Invite action
     * (create/invite split, mirroring {@code AdminProvisioningService}).
     *
     * <p>{@code email} / {@code mobile} / {@code nickname} are read from the row; username defaults
     * to email‖mobile and status to INVITED (in {@code registerInvitedUser}). tenant_id is
     * auto-stamped by that insert for a normal tenant admin; for a super-admin / cross-tenant caller
     * (not auto-stamped) an explicit or own tenant is applied here, alongside an optional policyId.
     */
    private Long inviteFromRow(Map<String, Object> row) {
        String email = StringUtils.trimToNull(Objects.toString(row.get("email"), null));
        String mobile = StringUtils.trimToNull(Objects.toString(row.get("mobile"), null));
        String fullName = StringUtils.trimToNull(Objects.toString(row.get("nickname"), null));
        Assert.isTrue(email != null || mobile != null,
                "An email or mobile is required to create a user.");
        UserInfo user = service.registerInvitedUser(email, mobile, fullName);
        Long userId = user.getUserId();

        // Post-insert patch: preserve an explicitly chosen security policy, and stamp the tenant for a
        // super-admin / cross-tenant caller whose insert the ORM did not auto-stamp.
        Map<String, Object> patch = new HashMap<>();
        patch.put(ModelConstant.ID, userId);
        Object policyId = row.get("policyId");
        if (policyId != null) {
            patch.put("policyId", policyId);
        }
        if (SystemConfig.env.isEnableMultiTenancy()) {
            var context = ContextHolder.getContext();
            if (context != null && context.isCrossTenant()) {
                Object tenantId = row.getOrDefault(ModelConstant.TENANT_ID, context.getTenantId());
                if (tenantId != null) {
                    patch.put(ModelConstant.TENANT_ID, tenantId);
                }
            }
        }
        if (patch.size() > 1) {
            modelService.updateOne(MODEL, patch);
        }
        return userId;
    }

    /** Fetch a just-created account as a REFERENCE-converted map for the *AndFetch endpoints. */
    private Map<String, Object> fetchRef(Long userId) {
        FlexQuery flexQuery = new FlexQuery(new Filters().eq(ModelConstant.ID, userId));
        flexQuery.setConvertType(ConvertType.REFERENCE);
        return modelService.searchOne(MODEL, flexQuery).orElse(null);
    }

    /**
     * Typed shadow of the generic {@code /UserAccount/updateOne}. The
     * {@code roles} ManyToMany cascades into {@code user_role_rel} through the
     * generic ORM write, which does NOT publish {@code UserRoleRelChangedEvent}
     * — so a role change made by editing this form would otherwise leave the
     * user's cached PermissionInfo stale until the 1h TTL. Body mirrors
     * {@code ModelController.updateOne} (so non-roles updates are unchanged);
     * we additionally evict this user when the payload touched roles. Spring
     * routes here over the templated {@code /{modelName}/updateOne} (literal
     * path is more specific).
     */
    @Operation(summary = "Update a UserAccount — evicts the user's cached permissions when roles change")
    @PostMapping("/updateOne")
    @DataMask
    public ApiResponse<Boolean> updateOne(@RequestBody Map<String, Object> row) {
        Assert.notNull(row.get("id"), "`id` cannot be null or missing when updating data!");
        IdUtils.formatMapId(MODEL, row);
        boolean ok = modelService.updateOne(MODEL, row);
        evictIfRolesTouched(row);
        return ApiResponse.success(ok);
    }

    @Operation(summary = "Update a UserAccount and fetch — evicts the user's cached permissions when roles change")
    @PostMapping("/updateOneAndFetch")
    @DataMask
    public ApiResponse<Map<String, Object>> updateOneAndFetch(@RequestBody Map<String, Object> row) {
        Assert.notEmpty(row, "The data to be updated cannot be empty!");
        Assert.notNull(row.get("id"), "`id` cannot be null or missing when updating data!");
        IdUtils.formatMapId(MODEL, row);
        Map<String, Object> result = modelService.updateOneAndFetch(MODEL, row, ConvertType.REFERENCE);
        evictIfRolesTouched(row);
        return ApiResponse.success(result);
    }

    /**
     * Typed shadow of the generic {@code /UserAccount/searchPage}. {@link UserAccount}
     * is not multi-tenant, so the generic endpoint would return every tenant's
     * accounts to a tenant admin — {@link #scopeByTenant} confines the result.
     * Spring routes here over the templated {@code /{modelName}/searchPage}
     * (literal path is more specific).
     */
    @Operation(summary = "Search UserAccount page — tenant-scoped (super-admin sees the cross-tenant admin roster)")
    @PostMapping("/searchPage")
    @DataMask
    public ApiResponse<Page<Map<String, Object>>> searchPage(@RequestBody(required = false) QueryParams queryParams) {
        if (queryParams == null) {
            queryParams = new QueryParams();
        }
        FlexQuery flexQuery = QueryParams.convertParamsToFlexQuery(queryParams);
        flexQuery.setFilters(scopeByTenant(flexQuery.getFilters()));
        Page<Map<String, Object>> page = Page.of(queryParams.getPageNumber(), queryParams.getPageSize());
        return ApiResponse.success(modelService.searchPage(MODEL, flexQuery, page));
    }

    /**
     * Typed shadow of the generic {@code /UserAccount/searchList} — same
     * tenant-scoping as {@link #searchPage}.
     */
    @Operation(summary = "Search UserAccount list — tenant-scoped (super-admin sees the cross-tenant admin roster)")
    @PostMapping("/searchList")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> searchList(@RequestBody(required = false) SearchListParams searchListParams) {
        if (searchListParams == null) {
            searchListParams = new SearchListParams();
        }
        FlexQuery flexQuery = SearchListParams.convertParamsToFlexQuery(searchListParams);
        flexQuery.setFilters(scopeByTenant(flexQuery.getFilters()));
        return ApiResponse.success(modelService.searchList(MODEL, flexQuery));
    }

    /**
     * Re-scope a UserAccount list read. {@link UserAccount} is now framework-multiTenant, so a normal
     * tenant user's reads are auto-filtered to their tenant by the ORM (nothing to add) and a generic
     * cross-tenant/system caller sees everything. Only the platform super-admin needs custom scoping
     * (requirement 2): the cross-tenant admin roster PLUS the super-admin's own tenant — see
     * {@link #scopeToAdminAccounts}. Single-tenant deployments are untouched. by-key paths (login,
     * {@code getMyAccount}, invite) never flow through search.
     */
    private Filters scopeByTenant(Filters filters) {
        if (!SystemConfig.env.isEnableMultiTenancy()) {
            return filters;   // single-tenant: no tenant dimension
        }
        var context = ContextHolder.getContext();
        Set<String> roleCodes = context == null ? null : context.getRoleCodes();
        if (roleCodes == null || !roleCodes.contains(RoleConstant.CODE_SUPER_ADMIN)) {
            return filters;   // non-super-admin: the ORM already auto-filters reads to the caller's tenant
        }
        return scopeToAdminAccounts(filters);
    }

    /**
     * The platform super-admin's account list (requirement 2): every account holding a
     * {@code SUPER_ADMIN} or {@code TENANT_ADMIN} role across all tenants, PLUS every account in the
     * super-admin's own (platform) tenant. Roster resolved via grants (admin role codes →
     * {@code user_role_rel} → user ids); the super-admin context is crossTenant so both reads span
     * every tenant ({@code Role} / {@code UserRoleRel} are multiTenant).
     */
    private Filters scopeToAdminAccounts(Filters filters) {
        List<Long> adminRoleIds = roleService.searchList(new Filters().in(Role::getCode, ADMIN_ROLE_CODES))
                .stream().map(Role::getId).toList();
        List<Long> userIds = adminRoleIds.isEmpty() ? List.of()
                : userRoleRelService.searchList(new Filters().in(UserRoleRel::getRoleId, adminRoleIds))
                        .stream().map(UserRoleRel::getUserId).distinct().toList();
        // Accounts holding an admin role (empty → sentinel -1L, avoids an ill-defined empty IN)...
        Filters roster = new Filters().in(ModelConstant.ID, userIds.isEmpty() ? List.of(-1L) : userIds);
        // ...OR any account in the super-admin's own (platform) tenant.
        Long ownTenant = ContextHolder.getContext() == null ? null : ContextHolder.getContext().getTenantId();
        Filters scope = ownTenant == null ? roster
                : Filters.or(roster, new Filters().eq(ModelConstant.TENANT_ID, ownTenant));
        return filters == null ? scope : Filters.and(filters, scope);
    }

    /**
     * A UserAccount write only affects that one user's PermissionInfo, so evict
     * exactly that user when the payload carried the {@code roles} field. No-op
     * for non-roles updates (pure pass-through, matching the generic endpoint).
     * Runs after the update call returns (its own transaction has committed),
     * so there's no pre-commit stale-reload race.
     */
    private void evictIfRolesTouched(Map<String, Object> row) {
        if (!row.containsKey(ROLES_FIELD)) return;
        Object idObj = row.get("id");
        Long userId = idObj instanceof Number n ? Long.valueOf(n.longValue())
                : idObj != null ? Long.valueOf(idObj.toString()) : null;
        if (userId == null) return;
        Long tenantId = ContextHolder.getContext() == null ? null
                : ContextHolder.getContext().getTenantId();
        permissionCacheInvalidator.evictBatch(tenantId, Set.of(userId));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = CookieUtils.getCookie(request, BaseConstant.SESSION_ID);
        cacheService.clear(RedisConstant.SESSION + sessionId);
        CookieUtils.clearCookie(response, BaseConstant.SESSION_ID);
        return ApiResponse.success();
    }

    @Operation(summary = "Lock User Account")
    @PostMapping("/lockAccount")
    public ApiResponse<Void> lockAccount(@RequestParam @NotNull Long id) {
        validateNotSelf(id, "lock");
        service.lockAccount(id);
        return ApiResponse.success();
    }

    @Operation(summary = "Unlock User Account")
    @PostMapping("/unlockAccount")
    public ApiResponse<Void> unlockAccount(@RequestParam @NotNull Long id,
                                           @RequestBody UnlockAccountDTO unlockAccountDTO) {
        validateNotSelf(id, "unlock");
        service.unlockAccount(id, unlockAccountDTO.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "Batch Unlock User Accounts")
    @PostMapping("/unlockAccounts")
    public ApiResponse<Void> unlockAccounts(@RequestBody @Valid UnlockAccountsDTO unlockAccountsDTO) {
        List<Long> userIds = unlockAccountsDTO.getIds();
        Long currentUserId = ContextHolder.getContext().getUserId();
        if (currentUserId != null && userIds.contains(currentUserId)) {
            throw new BusinessException("You cannot unlock your own account.");
        }
        service.unlockAccounts(userIds, unlockAccountsDTO.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "Invite / re-invite a user — emails a set-password link (for accounts that "
            + "have not set a password yet)")
    @PostMapping("/invite")
    public ApiResponse<Void> invite(@RequestParam @NotNull Long id) {
        Long currentUserId = ContextHolder.getContext() == null ? null
                : ContextHolder.getContext().getUserId();
        invitationService.invite(id, currentUserId);
        return ApiResponse.success();
    }

    private void validateNotSelf(Long userId, String action) {
        Long currentUserId = ContextHolder.getContext().getUserId();
        if (currentUserId != null && currentUserId.equals(userId)) {
            throw new BusinessException("You cannot " + action + " your own account.");
        }
    }

    @Operation(summary = "changeMyPassword")
    @PostMapping("/changeMyPassword")
    public ApiResponse<Void> changeMyPassword(@RequestBody @Valid ChangePasswordDTO changePasswordDTO) {
        service.changeMyPassword(changePasswordDTO.getCurrentPassword(), changePasswordDTO.getNewPassword());
        return ApiResponse.success();
    }

    @Operation(summary = "getMyAccount")
    @GetMapping("/getMyAccount")
    public ApiResponse<UserAccount> getMyAccount() {
        Long userId = ContextHolder.getContext().getUserId();
        try {
            Optional<UserAccount> accountOpt = service.getById(userId);

            if (accountOpt.isEmpty()) {
                log.warn("Current user account not found for ID: {}", userId);
                return new ApiResponse<>(ResponseCode.USER_NOT_FOUND.getCode(), "Current user account not found.",
                        null);
            }
            UserAccount account = accountOpt.get();
            // Mask sensitive fields before returning
            account.setPassword(null);
            account.setPasswordSalt(null);
            return ApiResponse.success(account);
        } catch (Exception e) {
            log.error("Error fetching current user account for ID: {}", userId, e);
            return new ApiResponse<>(ResponseCode.ERROR.getCode(), "Failed to retrieve user account.", null);
        }
    }

    @Operation(summary = "saveMyAccount")
    @PostMapping("/saveMyAccount")
    public ApiResponse<Void> saveMyAccount(@RequestBody @Valid UserAccountDTO myAccountDTO) {
        Long currentUserId;
        try {
            currentUserId = ContextHolder.getContext().getUserId();
            if (currentUserId == null) {
                log.warn("Attempt to save current account without authenticated context.");
                return new ApiResponse<>(ResponseCode.UNAUTHORIZED.getCode(), "User not authenticated.", null);
            }
        } catch (Exception e) {
            log.error("Error retrieving user ID from context", e);
            return new ApiResponse<>(ResponseCode.ERROR.getCode(), "Could not determine current user.", null);
        }

        try {
            UserAccount existingAccount = service.getById(currentUserId)
                    .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND,
                            "Current user account not found for update."));

            existingAccount.setNickname(myAccountDTO.getNickname());
            existingAccount.setEmail(myAccountDTO.getEmail());
            existingAccount.setMobile(myAccountDTO.getMobile());

            boolean success = service.updateOne(existingAccount);

            if (success) {
                log.info("User account updated successfully for user ID: {}", currentUserId);
                return ApiResponse.success();
            } else {
                log.error("Failed to update user account for user ID: {}. updateOne returned false.", currentUserId);
                return new ApiResponse<>(ResponseCode.ERROR.getCode(), "Failed to update user account.", null);
            }
        } catch (BusinessException be) {
            log.warn("BusinessException while saving account for user ID {}: {}", currentUserId, be.getMessage());
            return new ApiResponse<>(be.getResponseCode() != null ? be.getResponseCode().getCode()
                    : ResponseCode.BUSINESS_EXCEPTION.getCode(), be.getMessage(), null);
        } catch (Exception e) {
            log.error("Error saving current user account for ID: {}", currentUserId, e);
            return new ApiResponse<>(ResponseCode.ERROR.getCode(), "Failed to save user account.", null);
        }
    }
}