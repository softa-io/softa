package io.softa.starter.user.controller;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Supplier;
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
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.DataMask;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.AggFunctions;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.domain.PivotTable;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.vo.ModelReference;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.dto.BulkUpdateParams;
import io.softa.framework.web.dto.CountParams;
import io.softa.framework.web.dto.CountResult;
import io.softa.framework.web.dto.GetByIdParams;
import io.softa.framework.web.dto.GetByIdsParams;
import io.softa.framework.web.dto.QueryParams;
import io.softa.framework.web.dto.SearchListParams;
import io.softa.framework.web.dto.SearchNameParams;
import io.softa.framework.web.dto.SimpleAggParams;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.utils.CookieUtils;
import io.softa.starter.user.dto.ChangePasswordDTO;
import io.softa.starter.user.dto.WorkContacts;
import io.softa.starter.user.dto.SetFirstPasswordDTO;
import io.softa.starter.user.dto.FreezeAccountDTO;
import io.softa.starter.user.dto.ResetWorkContactsDTO;
import io.softa.starter.user.dto.FreezeAccountsDTO;
import io.softa.starter.user.dto.UnbindAndReinviteDTO;
import io.softa.starter.user.dto.UserAccountDTO;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.service.PermissionCacheInvalidator;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserRosterScope;

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
    private static final String PROFILE_FIELD = "profileId";
    /** {@link UserAccount#getLocked()} — derived per row, never stored. */
    private static final String LOCKED_FIELD = "locked";
    /** {@link UserAccount#getConsultant()} — set when the platform mints the membership, never by a
     *  write that arrives here. See {@link #dropConsultantFlag}. */
    private static final String CONSULTANT_FIELD = "consultant";

    @Autowired
    private CacheService cacheService;

    @Autowired
    private ModelService<Long> modelService;

    @Autowired
    private PermissionCacheInvalidator permissionCacheInvalidator;

    @Autowired
    private UserInvitationService invitationService;

    /** Cross-tenant roster window + bounds, shared with the other endpoints that open another
     *  tenant's account (see UserRosterScope for why the bounds must be identical). */
    @Autowired
    private UserRosterScope rosterScope;

    @Autowired
    private UserIdentityService identityService;

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
     * auto-stamped by that insert — for every caller, super-admin included — so a {@code tenantId} in
     * the row is ignored: this endpoint creates a user in the caller's own tenant. Provisioning
     * another tenant's first admin is a different operation with its own tenant argument
     * ({@code AdminProvisioningService}). Only {@code policyId} is patched back, because
     * {@code registerInvitedUser} does not take it.
     */
    private Long inviteFromRow(Map<String, Object> row) {
        String email = StringUtils.trimToNull(Objects.toString(row.get("email"), null));
        String mobile = StringUtils.trimToNull(Objects.toString(row.get("mobile"), null));
        String fullName = StringUtils.trimToNull(Objects.toString(row.get("nickname"), null));
        Assert.isTrue(email != null || mobile != null,
                "An email or mobile is required to create a user.");
        UserInfo user = service.registerInvitedUser(email, mobile, fullName);
        Long userId = user.getUserId();

        // Post-insert patch: preserve an explicitly chosen security policy, which
        // registerInvitedUser has no parameter for.
        Object policyId = row.get("policyId");
        if (policyId != null) {
            Map<String, Object> patch = new HashMap<>();
            patch.put(ModelConstant.ID, userId);
            patch.put("policyId", policyId);
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
        dropDerivedLock(row);
        dropConsultantFlag(row);
        boolean ok = onRosterAccounts(List.of(idOf(row)), () -> modelService.updateOne(MODEL, row));
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
        dropDerivedLock(row);
        dropConsultantFlag(row);
        Map<String, Object> result = onRosterAccounts(List.of(idOf(row)),
                () -> modelService.updateOneAndFetch(MODEL, row, ConvertType.REFERENCE));
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
        boolean perRow = !flexQuery.isAggregate();
        boolean borrowed = perRow && borrowProfileId(flexQuery);
        Page<Map<String, Object>> page = Page.of(queryParams.getPageNumber(), queryParams.getPageSize());
        return ApiResponse.success(rosterScope.call(() -> {
            flexQuery.setFilters(rosterScope.scopeByTenant(flexQuery.getFilters()));
            Page<Map<String, Object>> result = modelService.searchPage(MODEL, flexQuery, page);
            if (perRow) {
                stampPasswordLock(result == null ? null : result.getRows(), borrowed);
            }
            return result;
        }));
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
        boolean perRow = !flexQuery.isAggregate();
        boolean borrowed = perRow && borrowProfileId(flexQuery);
        return ApiResponse.success(rosterScope.call(() -> {
            flexQuery.setFilters(rosterScope.scopeByTenant(flexQuery.getFilters()));
            List<Map<String, Object>> rows = modelService.searchList(MODEL, flexQuery);
            return perRow ? stampPasswordLock(rows, borrowed) : rows;
        }));
    }

    /**
     * Typed shadow of the generic {@code /UserAccount/getById} — the detail read behind the roster.
     *
     * <p>The list the platform super-admin browses spans tenants ({@link #searchPage} /
     * {@link #searchList} above), but the generic getById ran tenant-filtered, so opening any row
     * from another tenant answered "Record Not Found". Same window, same caller gate, and the SAME
     * BOUNDS: the detail read is re-checked against {@link #scopeToAdminAccounts}, so the super-admin
     * opens exactly what the roster lists — every tenant's admins plus its own tenant's accounts —
     * and an id outside that roster answers like a nonexistent record. Everyone else reads exactly
     * what the generic path read.
     */
    @Operation(summary = "Get one account by id — the platform super-admin reads its cross-tenant roster")
    @PostMapping("/getById")
    @DataMask
    public ApiResponse<Map<String, Object>> getById(@RequestBody GetByIdParams getByIdParams) {
        Assert.notNull(getByIdParams.getId(), "The ID of the data to be read cannot be null!");
        ContextHolder.getContext().setEffectiveDate(getByIdParams.getEffectiveDate());
        Long id = IdUtils.formatId(MODEL, getByIdParams.getId());
        SubQueries subQueries = new SubQueries();
        if (getByIdParams.getSubQueries() != null && !getByIdParams.getSubQueries().isEmpty()) {
            subQueries.setQueryMap(getByIdParams.getSubQueries());
        }
        boolean borrowed = needsProfileId(getByIdParams.getFields());
        List<String> fields = borrowed ? withProfileId(getByIdParams.getFields()) : getByIdParams.getFields();
        return ApiResponse.success(rosterScope.call(() -> {
            // Roster membership first, for EVERYONE — the same bounds the list reads apply, consultant
            // memberships excluded. This used to ask only for the super-admin; an ordinary tenant admin
            // skipped it and read the row straight through the ORM's tenant filter, which a consultant's
            // membership in that tenant passes. So the User Accounts page said the row did not exist
            // while getById handed over its name and contacts to anyone holding the id — and the audit
            // panel prints that id beside every change the consultant makes. Same answer as a
            // nonexistent record, so the check confirms nothing about ids it hides.
            if (modelService.count(MODEL,
                    rosterScope.scopeByTenant(new Filters().eq(ModelConstant.ID, id))) == 0) {
                return null;
            }
            Map<String, Object> row = modelService
                    .getById(MODEL, id, fields, subQueries, ConvertType.REFERENCE)
                    .orElse(null);
            if (row != null) {
                stampPasswordLock(List.of(row), borrowed);
            }
            return row;
        }));
    }

    // ─── Typed shadows of the remaining generic endpoints ─────────────────────────────────────
    //
    // This controller shadows searchPage / searchList / getById / updateOne so that those pass the
    // roster scope. Every generic endpoint it did NOT shadow still resolved: Spring falls through to
    // ModelController's templated /{modelName}/..., and none of those apply the scope. So a tenant
    // admin holding the ordinary account permissions could POST /UserAccount/deleteByIds against a
    // consultant's hidden row, /UserAccount/getByIds to read its name and contacts, or
    // /UserAccount/updateByFilter to rewrite every row's consultant flag at once. The list hid the
    // rows; the by-id surface handed them over.
    //
    // Shadowed here one by one, and pinned by UserAccountShadowsGenericEndpointsTest so a generic
    // endpoint added to ModelController later fails a test instead of quietly reopening the gap.
    // The four copy endpoints are not shadowed: UserAccount is copyable = false, so the framework
    // refuses them before any row is read — a membership is not a thing to duplicate.

    @Operation(summary = "Get UserAccounts by IDs — roster-scoped; ids outside the roster are dropped, not refused")
    @PostMapping("/getByIds")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> getByIds(@RequestBody GetByIdsParams getByIdsParams) {
        ContextHolder.getContext().setEffectiveDate(getByIdsParams.getEffectiveDate());
        List<Long> ids = IdUtils.formatIds(MODEL, getByIdsParams.getIds());
        Assert.notEmpty(ids, "The IDs of the data to be read cannot be empty!");
        validateIdList(ids);
        SubQueries subQueries = new SubQueries();
        if (!CollectionUtils.isEmpty(getByIdsParams.getSubQueries())) {
            subQueries.setQueryMap(getByIdsParams.getSubQueries());
        }
        return ApiResponse.success(rosterScope.call(() -> {
            // Dropped rather than refused, matching searchPage: a batch read that named one hidden id
            // should still answer for the rest, and a refusal would confirm the hidden id exists.
            List<Long> visible = visibleIds(ids);
            if (visible.isEmpty()) {
                return List.of();
            }
            return modelService.getByIds(MODEL, visible, getByIdsParams.getFields(), subQueries,
                    ConvertType.REFERENCE);
        }));
    }

    @Operation(summary = "Unmask one field of a UserAccount — roster-scoped")
    @GetMapping("/getUnmaskedField")
    public ApiResponse<String> getUnmaskedField(@RequestParam Long id, @RequestParam String field,
                                                @RequestParam(required = false) LocalDate effectiveDate) {
        ContextHolder.getContext().setEffectiveDate(effectiveDate);
        Long rowId = IdUtils.formatId(MODEL, id);
        return ApiResponse.success(onRosterAccounts(List.of(rowId),
                () -> modelService.getUnmaskedField(MODEL, rowId, field)));
    }

    @Operation(summary = "Unmask several fields of a UserAccount — roster-scoped")
    @GetMapping("/getUnmaskedFields")
    public ApiResponse<Map<String, Object>> getUnmaskedFields(@RequestParam Long id,
                                                              @RequestParam List<String> fields,
                                                              @RequestParam(required = false) LocalDate effectiveDate) {
        ContextHolder.getContext().setEffectiveDate(effectiveDate);
        Long rowId = IdUtils.formatId(MODEL, id);
        return ApiResponse.success(onRosterAccounts(List.of(rowId),
                () -> modelService.getUnmaskedFields(MODEL, rowId, fields)));
    }

    @Operation(summary = "Update several UserAccounts by ID — roster-scoped; evicts cached permissions when roles change")
    @PostMapping("/updateList")
    public ApiResponse<Boolean> updateList(@RequestBody List<Map<String, Object>> rows) {
        Assert.notEmpty(rows, "The data to be updated cannot be empty!");
        this.validateBatchSize(rows.size());
        IdUtils.formatMapIds(MODEL, rows);
        rows.forEach(row -> {
            dropDerivedLock(row);
            dropConsultantFlag(row);
        });
        List<Long> ids = rows.stream().map(UserAccountController::idOf).toList();
        boolean ok = onRosterAccounts(ids, () -> modelService.updateList(MODEL, rows));
        rows.forEach(this::evictIfRolesTouched);
        return ApiResponse.success(ok);
    }

    @Operation(summary = "Update several UserAccounts by ID and fetch — roster-scoped; evicts cached permissions when roles change")
    @PostMapping("/updateListAndFetch")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> updateListAndFetch(@RequestBody List<Map<String, Object>> rows) {
        Assert.notEmpty(rows, "The data to be updated cannot be empty!");
        this.validateBatchSize(rows.size());
        IdUtils.formatMapIds(MODEL, rows);
        rows.forEach(row -> {
            dropDerivedLock(row);
            dropConsultantFlag(row);
        });
        List<Long> ids = rows.stream().map(UserAccountController::idOf).toList();
        List<Map<String, Object>> result = onRosterAccounts(ids,
                () -> modelService.updateListAndFetch(MODEL, rows, ConvertType.REFERENCE));
        rows.forEach(this::evictIfRolesTouched);
        return ApiResponse.success(result);
    }

    /**
     * Bulk update within the roster. {@code roles} is refused here rather than accepted blind: a
     * filter names no ids, so there would be nobody to evict, and a role change that leaves every
     * affected user's cached permissions stale for the TTL is worse than no bulk path at all.
     */
    @Operation(summary = "Update UserAccounts by filter — roster-scoped; roles must be assigned per account")
    @PostMapping("/updateByFilter")
    public ApiResponse<Integer> updateByFilter(@RequestBody BulkUpdateParams bulkUpdateParams) {
        Map<String, Object> values = bulkUpdateParams.getValues();
        Assert.notEmpty(values, "The updated data cannot be empty!");
        Assert.notTrue(values.containsKey(ROLES_FIELD),
                "Roles are assigned per account, not by filter.");
        dropDerivedLock(values);
        dropConsultantFlag(values);
        ContextHolder.getContext().setEffectiveDate(bulkUpdateParams.getEffectiveDate());
        return ApiResponse.success(rosterScope.call(() -> modelService.updateByFilter(MODEL,
                rosterScope.scopeByTenant(bulkUpdateParams.getFilters()), values)));
    }

    @Operation(summary = "Delete one UserAccount — roster-scoped")
    @PostMapping("/deleteById")
    public ApiResponse<Boolean> deleteById(@RequestParam Long id) {
        Long rowId = IdUtils.formatId(MODEL, id);
        return ApiResponse.success(onRosterAccounts(List.of(rowId),
                () -> modelService.deleteById(MODEL, rowId)));
    }

    @Operation(summary = "Delete several UserAccounts — roster-scoped")
    @PostMapping("/deleteByIds")
    public ApiResponse<Boolean> deleteByIds(@RequestParam List<Long> ids) {
        Assert.notEmpty(ids, "The IDs of the data to be deleted cannot be empty!");
        validateIdList(ids);
        List<Long> rowIds = IdUtils.formatIds(MODEL, ids);
        return ApiResponse.success(onRosterAccounts(rowIds,
                () -> modelService.deleteByIds(MODEL, rowIds)));
    }

    @Operation(summary = "Search UserAccount display names — roster-scoped")
    @PostMapping("/searchName")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> searchName(
            @RequestBody(required = false) SearchNameParams searchNameParams) {
        FlexQuery flexQuery = SearchNameParams.convertParamsToFlexQuery(searchNameParams);
        return ApiResponse.success(rosterScope.call(() -> {
            flexQuery.setFilters(rosterScope.scopeByTenant(flexQuery.getFilters()));
            return modelService.searchName(MODEL, flexQuery);
        }));
    }

    @Operation(summary = "Aggregate over UserAccounts — roster-scoped")
    @PostMapping("/searchSimpleAgg")
    @DataMask
    public ApiResponse<Map<String, Object>> searchSimpleAgg(@RequestBody SimpleAggParams simpleAggParams) {
        ContextHolder.getContext().setEffectiveDate(simpleAggParams.getEffectiveDate());
        Assert.notTrue(AggFunctions.isEmpty(simpleAggParams.getAggFunctions()), "`aggFunctions` cannot be null!");
        return ApiResponse.success(rosterScope.call(() -> {
            FlexQuery flexQuery = new FlexQuery(rosterScope.scopeByTenant(simpleAggParams.getFilters()));
            flexQuery.setAggFunctions(simpleAggParams.getAggFunctions());
            return modelService.searchOne(MODEL, flexQuery).orElse(null);
        }));
    }

    @Operation(summary = "Pivot over UserAccounts — roster-scoped")
    @PostMapping("/searchPivot")
    @DataMask
    public ApiResponse<PivotTable> searchPivot(@RequestBody(required = false) QueryParams queryParams) {
        QueryParams params = queryParams == null ? new QueryParams() : queryParams;
        FlexQuery flexQuery = QueryParams.convertParamsToFlexQuery(params);
        flexQuery.setSplitBy(params.getSplitBy());
        return ApiResponse.success(rosterScope.call(() -> {
            flexQuery.setFilters(rosterScope.scopeByTenant(flexQuery.getFilters()));
            return modelService.searchPivot(MODEL, flexQuery);
        }));
    }

    @Operation(summary = "Count UserAccounts — roster-scoped")
    @PostMapping("/count")
    @DataMask
    public ApiResponse<CountResult> count(@RequestBody(required = false) CountParams countParams) {
        CountParams params = countParams == null ? new CountParams() : countParams;
        ContextHolder.getContext().setEffectiveDate(params.getEffectiveDate());
        return ApiResponse.success(rosterScope.call(() -> {
            Filters scoped = rosterScope.scopeByTenant(params.getFilters());
            CountResult result = new CountResult();
            List<String> groupBy = params.getGroupBy();
            if (!CollectionUtils.isEmpty(groupBy)) {
                Assert.allNotBlank(groupBy, "`groupBy` cannot contain empty value: {0}", groupBy);
                FlexQuery flexQuery = new FlexQuery(scoped, params.getOrders());
                flexQuery.setFields(new HashSet<>(groupBy));
                flexQuery.setGroupBy(groupBy);
                flexQuery.setConvertType(ConvertType.TYPE_CAST);
                result.setGroups(modelService.searchList(MODEL, flexQuery));
            } else {
                result.setTotal(modelService.count(MODEL, scoped));
            }
            return result;
        }));
    }

    /**
     * Fill the derived {@code locked} flag on account rows: true when the row's PERSON currently has
     * their password login locked ({@code UserIdentity.passwordLockedUntil} in the future).
     *
     * <p>The lock is a SECOND AXIS, not a status value — the row keeps reading Active / Frozen /
     * whatever, and the list badges the lock next to it. Deriving it here rather than storing a
     * column on the membership is what keeps the two from disagreeing: the lock belongs to the
     * person (so it is the same across their tenants) and it expires on a clock nothing writes to.
     *
     * <p>ONE query per response, not per row: the page's people are collected first and resolved
     * together. A roster of fifty rows is otherwise fifty credential reads.
     */
    private List<Map<String, Object>> stampPasswordLock(List<Map<String, Object>> rows, boolean borrowedProfileId) {
        stampPasswordLock(rows);
        if (borrowedProfileId && rows != null) {
            // Give the field list back exactly as asked for. The caller never requested profileId;
            // leaving it in adds a person id to a response that was scoped to other columns.
            rows.forEach(row -> row.remove(PROFILE_FIELD));
        }
        return rows;
    }

    /**
     * Read the row's person even when the caller asked for a narrower field list.
     *
     * <p>The account table sends an explicit field set built from its own declared columns, and
     * profileId is not one of them — the ORM only adds id / version / sliceId to a caller-supplied
     * set, so {@link #profileIdOf} found nothing on every row of the real request and the lock badge
     * was stamped false for everyone. An empty/absent set already means every field, so it is left
     * alone; a non-empty one is widened here and narrowed back in {@link #stampPasswordLock}.
     *
     * <p>Only for a per-row read. An AGGREGATE query ({@link FlexQuery#isAggregate()} — set by any
     * of groupBy / splitBy / aggFunctions) returns grouped rows, not accounts: there is no account
     * for a lock to belong to, and adding a plain column to the selection is not a widening but a
     * different query — profileId would either have to join the GROUP BY (splitting every group by
     * person) or be wrapped in an aggregate function. Both change the caller's result, so an
     * aggregate read is passed through exactly as it was sent; see the guards in
     * {@link #searchPage} / {@link #searchList} for the matching skip of the stamping.
     *
     * @return whether profileId was borrowed, i.e. must be removed from the rows afterwards
     */
    private static boolean borrowProfileId(FlexQuery flexQuery) {
        if (!needsProfileId(flexQuery.getFields())) {
            return false;
        }
        flexQuery.setFields(withProfileId(flexQuery.getFields()));
        return true;
    }

    /** Whether this caller-supplied field set has to be widened — see {@link #borrowProfileId}. */
    private static boolean needsProfileId(List<String> fields) {
        return !CollectionUtils.isEmpty(fields) && !fields.contains(PROFILE_FIELD);
    }

    /** A widened COPY: the caller's own list may be immutable, and mutating it is not ours to do. */
    private static List<String> withProfileId(List<String> fields) {
        List<String> widened = new ArrayList<>(fields);
        widened.add(PROFILE_FIELD);
        return widened;
    }

    private List<Map<String, Object>> stampPasswordLock(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        Set<Long> people = rows.stream().map(UserAccountController::profileIdOf)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> locked = identityService.findPasswordLockedProfiles(people);
        for (Map<String, Object> row : rows) {
            Long profileId = profileIdOf(row);
            // An account with no person cannot carry a person's lock — false, never null: the badge
            // reads a boolean and a missing key would render as "unknown" in the UI.
            row.put(LOCKED_FIELD, profileId != null && locked.contains(profileId));
        }
        return rows;
    }

    /**
     * The row's person id, whatever shape the read left it in: a raw number, a string (ids are
     * serialized as strings on the paths a browser reads, which loses precision on a 19-digit long),
     * or the {@code {id, displayName}} pair {@link ConvertType#REFERENCE} renders a MANY_TO_ONE as.
     */
    private static Long profileIdOf(Map<String, Object> row) {
        Object value = row == null ? null : row.get(PROFILE_FIELD);
        if (value instanceof ModelReference reference) {
            value = reference.getId();
        } else if (value instanceof Map<?, ?> reference) {
            value = reference.get(ModelConstant.ID);
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String id = value == null ? null : value.toString();
        // Not a number at all → no person to ask about, rather than a 500 on a list read.
        return StringUtils.isNumeric(id) ? Long.valueOf(id) : null;
    }


    /**
     * Drop the derived {@code locked} key from an update payload before it reaches the ORM.
     *
     * <p>{@link UserAccount#getLocked()} is also declared {@code readonly}, which is what
     * {@code ModelManager.getModelUpdatableFields} filters on. That is the DECLARATIVE answer and
     * the durable one — it covers every write path, this controller's and the five generic ones
     * this class does not shadow. What it does not cover is time: the attribute is read from the
     * {@code sys_field} ROW, not from the annotation, so the field stays writable until a boot with
     * a non-empty scanner scope — or a studio deploy — reconciles it, and production runs with an
     * empty scanner scope, which makes that reconciliation a release step rather than a boot side
     * effect. This strip is UNCONDITIONAL and consults no metadata, so it holds from the first
     * request after deploy, for the two verbs the UI actually calls.
     *
     * <p>What it prevents in that window: {@code {"id":…,"locked":true}} renders
     * {@code UPDATE user_account SET locked = ?} — persisting to the orphaned legacy column on an
     * upgraded database, or failing with unknown-column on a freshly converged one.
     *
     * <p>The create endpoints need no equivalent: {@link #inviteFromRow} reads named keys off the
     * row and never hands the map itself to the write pipeline, so a caller-supplied
     * {@code locked} has nothing to reach.
     */
    private static void dropDerivedLock(Map<String, Object> row) {
        row.remove(LOCKED_FIELD);
    }

    /**
     * Strip the consultant flag from an inbound write.
     *
     * <p>The flag is set exactly once, when the platform mints a consultant's membership, and every
     * roster read hides rows that carry it. Accepting it from a tenant-side write would let one call
     * turn an employee into a hidden row nobody in the tenant can find again, or — on a row the caller
     * should not have reached — turn a consultant's membership into a visible employment they can
     * then freeze and re-role. Silently dropped rather than refused, like the derived lock: a form
     * that round-trips the row as it read it must not fail for carrying a field it never edited.
     */
    private static void dropConsultantFlag(Map<String, Object> row) {
        row.remove(CONSULTANT_FIELD);
    }

    /**
     * The row's id as a Long, after {@link IdUtils#formatMapId} has normalised it.
     *
     * <p>Coerced here rather than through {@code IdUtils.formatId}: the key type of this model is
     * fixed, and the same coercion already sits in {@link #evictIfRolesTouched}. Going back through
     * the metadata for a fact the class knows would only add a second place for it to be wrong.
     */
    private static Long idOf(Map<String, Object> row) {
        Object raw = row.get(ModelConstant.ID);
        Assert.notNull(raw, "`id` cannot be null or missing when updating data!");
        return raw instanceof Number n ? Long.valueOf(n.longValue()) : Long.valueOf(raw.toString());
    }

    /**
     * The bounds {@code ModelController.validateIds} applies to a by-id list, restated because that
     * method is private: no null element (it would widen the IN to every row) and no more than the
     * framework's batch maximum (a by-id endpoint is not a way to walk, or empty, the table).
     *
     * <p>The null check is a stream rather than {@code Assert.allNotNull}, whose implementation is
     * {@code objects.contains(null)} — and {@code List.of(...)} throws NPE on that rather than
     * answering false. Spring binds a request parameter to an ArrayList so the generic endpoint
     * never meets it, but a caller passing an immutable list would get an NPE where it meant to get
     * a refusal.
     */
    private void validateIdList(List<Long> ids) {
        Assert.isTrue(ids.stream().allMatch(Objects::nonNull),
                "ids cannot contain null values: {0}", ids);
        this.validateBatchSize(ids.size());
    }

    /**
     * The ids among {@code ids} naming rows the caller administers. Must run inside
     * {@link UserRosterScope#call}, for the same reason the list reads must.
     */
    private List<Long> visibleIds(List<Long> ids) {
        FlexQuery q = new FlexQuery(List.of(ModelConstant.ID),
                rosterScope.scopeByTenant(new Filters().in(ModelConstant.ID, ids)));
        return modelService.searchList(MODEL, q).stream()
                .map(r -> UserAccountController.<Long>keyOf(r.get(ModelConstant.ID)))
                .toList();
    }

    private static <K extends Serializable> K keyOf(Object raw) {
        return IdUtils.formatId(MODEL, (Serializable) raw);
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

    // Lock / Unlock are gone (D21). A manual lock and the automatic password lockout (A8) were two
    // mechanisms for two different things wearing one name: the lockout reacts to guessing, lives
    // on the credential and expires by itself, while freezing is an administrator's decision about
    // a membership that only an administrator lifts.
    @Operation(summary = "Freeze a user account — suspends access until an administrator lifts it")
    @PostMapping("/freezeAccount")
    public ApiResponse<Void> freezeAccount(@RequestParam @NotNull Long id,
                                           @RequestBody FreezeAccountDTO freezeAccountDTO) {
        validateNotSelf(id, "freeze");
        onRosterAccounts(List.of(id), () -> service.freezeAccount(id, freezeAccountDTO.getReason()));
        return ApiResponse.success();
    }

    @Operation(summary = "Unfreeze a user account")
    @PostMapping("/unfreezeAccount")
    public ApiResponse<Void> unfreezeAccount(@RequestParam @NotNull Long id,
                                           @RequestBody FreezeAccountDTO freezeAccountDTO) {
        validateNotSelf(id, "unfreeze");
        onRosterAccounts(List.of(id), () -> service.unfreezeAccount(id, freezeAccountDTO.getReason()));
        return ApiResponse.success();
    }

    @Operation(summary = "Batch Unfreeze User Accounts")
    @PostMapping("/unfreezeAccounts")
    public ApiResponse<Void> unfreezeAccounts(@RequestBody @Valid FreezeAccountsDTO freezeAccountsDTO) {
        List<Long> userIds = freezeAccountsDTO.getIds();
        Long currentUserId = ContextHolder.getContext().getUserId();
        if (currentUserId != null && userIds.contains(currentUserId)) {
            throw new BusinessException("You cannot unfreeze your own account.");
        }
        onRosterAccounts(userIds, () -> service.unfreezeAccounts(userIds, freezeAccountsDTO.getReason()));
        return ApiResponse.success();
    }

    @Operation(summary = "Invite / re-invite a user — emails a set-password link (for accounts that "
            + "have not set a password yet)")
    @PostMapping("/invite")
    public ApiResponse<Void> invite(@RequestParam @NotNull Long id) {
        Long currentUserId = ContextHolder.getContext() == null ? null
                : ContextHolder.getContext().getUserId();
        onRosterAccounts(List.of(id), () -> invitationService.invite(id, currentUserId));
        return ApiResponse.success();
    }

    @Operation(summary = "Re-hire a former employee — reopens their closed account as Pending with "
            + "its previous work contacts; send a new invitation afterwards")
    @PostMapping("/rehire")
    public ApiResponse<Void> rehire(@RequestParam @NotNull Long id) {
        onOwnRosterAccount(id, () -> service.rehire(id));
        return ApiResponse.success();
    }

    @Operation(summary = "The work contacts this account's employee record holds — what Reset User "
            + "and Unbind & Re-invite will carry onto the membership")
    @GetMapping("/archiveWorkContacts")
    public ApiResponse<WorkContacts> archiveWorkContacts(@RequestParam @NotNull Long id) {
        // Read under the same admission as the operations that consume it: a caller who may reset
        // or re-invite this account may see what the record says it will be reset to. Wrapped in
        // onRosterAccounts for the same reason those are — a platform super-admin works a roster
        // that spans tenants, and the account may not be in the ambient one.
        return ApiResponse.success(onRosterAccounts(List.of(id),
                () -> service.archiveWorkContacts(id)));
    }

    @Operation(summary = "Reset a membership's work contacts — keeps the person, their password "
            + "and their roles; moves the login identifier with the contact and notifies the old address")
    @PostMapping("/resetWorkContacts")
    public ApiResponse<Void> resetWorkContacts(@RequestParam @NotNull Long id,
            @RequestBody @Valid ResetWorkContactsDTO dto) {
        onOwnRosterAccount(id, () -> service.resetWorkContacts(id, dto.getReason()));
        return ApiResponse.success();
    }

    @Operation(summary = "Unbind a membership from the wrong person, correct the work contacts "
            + "and re-invite it — invalidates any link the wrong person still holds")
    @PostMapping("/unbindAndReinvite")
    public ApiResponse<Void> unbindAndReinvite(@RequestParam @NotNull Long id,
            @RequestBody @Valid UnbindAndReinviteDTO dto) {
        // Not validateNotSelf-guarded like Freeze / Unfreeze: unbinding your OWN membership detaches
        // you from it, which is a foot-gun rather than a privilege escalation — and an admin who
        // was themselves bound to the wrong membership is exactly who needs this.
        Long currentUserId = ContextHolder.getContext() == null ? null
                : ContextHolder.getContext().getUserId();
        onOwnRosterAccount(id, () -> invitationService.unbindAndReinvite(
                id, dto.getReason(), currentUserId));
        return ApiResponse.success();
    }

    @Operation(summary = "Revoke the outstanding invitation — invalidates the link and returns "
            + "the account to Pending so it can be invited again")
    @PostMapping("/revokeInvitation")
    public ApiResponse<Void> revokeInvitation(@RequestParam @NotNull Long id) {
        onRosterAccounts(List.of(id), () -> invitationService.revokeInvitation(id));
        return ApiResponse.success();
    }

    /**
     * Run a by-id account OPERATION with the same reach as the roster reads. The account list the
     * platform super-admin acts from spans tenants ({@link #searchPage}), but these operations
     * resolved their target inside the caller's own tenant, so Lock / Unlock / Invite on any row
     * from another tenant failed with "User not found" (#686 — the operation twin of the getById
     * fix above). Same window, same bounds: the super-admin reaches roster members only, and an id
     * outside the roster gets the same answer as a nonexistent one. Every other caller stays outside
     * the window, so an operation that resolves its row through the ORM's tenant filter runs
     * tenant-locally — but a service method annotated {@code @CrossTenant} resolves its row WITHOUT
     * that filter whoever calls it, and needs {@link #onOwnRosterAccount} on top.
     */
    private void onRosterAccounts(List<Long> ids, Runnable op) {
        this.onRosterAccounts(ids, () -> {
            op.run();
            return null;
        });
    }

    /** The value-returning twin, for the reads that need the same reach as the operations. */
    private <T> T onRosterAccounts(List<Long> ids, Supplier<T> op) {
        return rosterScope.call(() -> {
            // For everyone, not the super-admin alone. The roster scope hides consultant memberships
            // from every LIST read; a by-id operation that skipped it let a tenant admin freeze,
            // re-role or delete a row the same page had just said does not exist — and freezing one
            // is exactly the drift UserRosterScope warns about: the platform's grant saying yes while
            // the membership says no. scopeByTenant carries the consultant predicate for every caller
            // and the roster bounds for the super-admin; for an ordinary caller the count also runs
            // under the ORM's tenant filter, so an id from another company gets the same answer here
            // as a nonexistent one.
            long visible = modelService.count(MODEL,
                    rosterScope.scopeByTenant(new Filters().in(ModelConstant.ID, ids)));
            if (visible != ids.stream().distinct().count()) {
                throw new BusinessException("User not found.");
            }
            return op.get();
        });
    }

    /**
     * Run a by-id operation whose service method is {@code @CrossTenant} — {@code rehire},
     * {@code resetWorkContacts}, {@code unbindAndReinvite} — bounded to a row the caller administers.
     *
     * <p>The annotation is there so the platform super-admin can act on a roster row that sits in
     * another company, but it waives the ORM's tenant filter for EVERY caller: the service loads
     * the row by id with no tenant clause, so a tenant HR holding the grant could post another
     * tenant's account id and reopen, re-address or unbind that membership. {@link
     * #onRosterAccounts} bounds only the super-admin (to the roster); this bounds everyone else to
     * their own tenant, by loading the row first and comparing its tenant to the caller's. The
     * refusal is the same "User not found." a nonexistent id gets, so the check does not confirm
     * that the id exists elsewhere.
     */
    private void onOwnRosterAccount(Long id, Runnable op) {
        onRosterAccounts(List.of(id), () -> {
            UserAccount row = service.getById(id)
                    .orElseThrow(() -> new BusinessException("User not found."));
            // Objects.equals: single-tenant deployments carry null on both sides.
            if (!rosterScope.isPlatformSuperAdmin()
                    && !Objects.equals(row.getTenantId(), ContextHolder.getContext().getTenantId())) {
                throw new BusinessException("User not found.");
            }
            op.run();
        });
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

    @Operation(summary = "Whether the logged-in person still owes a first password")
    @GetMapping("/mustSetMyPassword")
    public ApiResponse<Boolean> mustSetMyPassword() {
        return ApiResponse.success(service.mustSetMyPassword());
    }

    @Operation(summary = "setMyFirstPassword")
    @PostMapping("/setMyFirstPassword")
    public ApiResponse<Void> setMyFirstPassword(@RequestBody @Valid SetFirstPasswordDTO dto) {
        service.setMyFirstPassword(dto.getNewPassword());
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
            // No masking needed any more: the credential is not on this model. Keeping the old
            // setPassword(null) calls would not compile, and re-adding them elsewhere would only
            // re-create the leak this move removed.
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

            // Only the display name is the person's to edit here. The work email and mobile are
            // owned by the employee record and read-only on the account; a change to the login
            // contact goes through the verified flow on UserProfile. Copying them from the request
            // would let any signed-in person, with no verification code and no per-tenant
            // uniqueness check, redirect where this membership's invitations and notices are
            // delivered — and collide with uk_user_account_tenant_email on the way.
            existingAccount.setNickname(myAccountDTO.getNickname());

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