package io.softa.starter.user.controller;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.web.dto.GetByIdParams;
import io.softa.framework.web.dto.SearchListParams;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.dto.ResetWorkContactsDTO;
import io.softa.starter.user.dto.UnbindAndReinviteDTO;
import io.softa.starter.user.dto.UserAccountDTO;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.service.PermissionCacheInvalidator;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserRoleRelService;
import io.softa.starter.user.service.UserRosterScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the roles-eviction shadow on {@link UserAccountController}. Editing the
 * {@code roles} ManyToMany via a UserAccount update cascades into
 * {@code user_role_rel} through the generic ORM write, which does NOT publish
 * {@code UserRoleRelChangedEvent} — so the controller must evict that user's
 * cached PermissionInfo itself, and ONLY when the payload actually touched
 * roles.
 *
 * <p>{@code IdUtils.formatMapId} is a static that consults model metadata (not
 * loaded in a unit test), so it is mocked to a no-op; the {@code @DataMask}
 * aspect is inert when the method is invoked directly (no Spring proxy).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class UserAccountControllerTest {

    private UserAccountController controller;
    private ModelService modelService;
    private PermissionCacheInvalidator invalidator;

    /** {@code SystemConfig.env} is a @PostConstruct singleton; a plain unit test has to supply it. */
    private SystemConfig previousEnv;

    @AfterEach
    void restoreEnv() {
        SystemConfig.env = previousEnv;   // a static — do not leak it into the next test class
    }

    @BeforeEach
    void setUp() {
        previousEnv = SystemConfig.env;
        SystemConfig env = new SystemConfig();
        env.setEnableMultiTenancy(true);
        SystemConfig.env = env;
        controller = new UserAccountController();
        modelService = mock(ModelService.class);
        invalidator = mock(PermissionCacheInvalidator.class);
        ReflectionTestUtils.setField(controller, "modelService", modelService);
        ReflectionTestUtils.setField(controller, "permissionCacheInvalidator", invalidator);
        // The roster window + bounds moved to UserRosterScope; the controller now delegates. Bare
        // mocks are enough for the non-super-admin paths, which return before resolving the roster.
        installRosterScope(mock(RoleService.class));
    }

    /**
     * Every by-id path now asks the roster count before acting — for everyone, consultant rows
     * excluded (see UserAccountByIdHidesConsultantsTest). These cases are about what happens AFTER
     * that gate, so the row is simply visible. Set per case rather than in setUp, because other
     * cases rely on the mock's default 0 to assert the refusal.
     */
    private void rosterSeesTheRow() {
        when(modelService.count(eq("UserAccount"), any())).thenReturn(1L);
    }

    private static Map<String, Object> row(Object id, boolean withRoles) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("nickname", "Alice");
        if (withRoles) row.put("roles", List.of("r1", "r2"));
        return row;
    }

    @Test
    void updateOne_rolesChanged_evictsThatUser() {
        rosterSeesTheRow();
        when(modelService.updateOne(eq("UserAccount"), any())).thenReturn(true);

        try (MockedStatic<IdUtils> ignored = Mockito.mockStatic(IdUtils.class)) {
            inTenant(9L, () -> controller.updateOne(row(7L, true)));
        }

        verify(invalidator).evictBatch(9L, Set.of(7L));
    }

    @Test
    void updateOne_stringId_coercedAndEvicted() {
        rosterSeesTheRow();
        when(modelService.updateOne(eq("UserAccount"), any())).thenReturn(true);

        try (MockedStatic<IdUtils> ignored = Mockito.mockStatic(IdUtils.class)) {
            inTenant(9L, () -> controller.updateOne(row("7", true)));
        }

        verify(invalidator).evictBatch(9L, Set.of(7L));
    }

    @Test
    void updateOne_noRolesInPayload_doesNotEvict() {
        rosterSeesTheRow();
        when(modelService.updateOne(eq("UserAccount"), any())).thenReturn(true);

        try (MockedStatic<IdUtils> ignored = Mockito.mockStatic(IdUtils.class)) {
            inTenant(9L, () -> controller.updateOne(row(7L, false)));
        }

        verify(invalidator, never()).evictBatch(any(), any());
    }

    @Test
    void updateOneAndFetch_rolesChanged_evictsThatUser() {
        rosterSeesTheRow();
        when(modelService.updateOneAndFetch(eq("UserAccount"), any(), any()))
                .thenReturn(new HashMap<>());

        try (MockedStatic<IdUtils> ignored = Mockito.mockStatic(IdUtils.class)) {
            inTenant(9L, () -> controller.updateOneAndFetch(row(7L, true)));
        }

        verify(invalidator).evictBatch(9L, Set.of(7L));
    }

    // ─── the derived `locked` flag is not writable ───

    /**
     * The payload a browser sends when the account form was rendered from model metadata: every
     * displayed field comes back, {@code locked} among them, because the readonly flag the form
     * would have obeyed lives in {@code sys_field} and is not there until the metadata scanner has
     * run. The annotation on {@link UserAccount#getLocked()} is read from that same row, so in this
     * window the controller's own strip is the only thing standing between the request and
     * {@code UPDATE user_account SET locked = ?}.
     */
    private static Map<String, Object> rowWithLock(Object id) {
        Map<String, Object> row = row(id, false);
        row.put("locked", true);
        return row;
    }

    @Test
    void updateOne_doesNotWriteTheDerivedLock_butKeepsEveryOtherKey() {
        rosterSeesTheRow();
        when(modelService.updateOne(eq("UserAccount"), any())).thenReturn(true);
        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);

        try (MockedStatic<IdUtils> ignored = Mockito.mockStatic(IdUtils.class)) {
            inTenant(9L, () -> controller.updateOne(rowWithLock(7L)));
        }

        verify(modelService).updateOne(eq("UserAccount"), sent.capture());
        // Load-bearing: the key never reaches the ORM, so no SET clause is rendered for a column
        // that is orphaned on an upgraded database and absent on a converged one.
        assertThat(sent.getValue()).doesNotContainKey("locked");
        // ...and the strip is surgical — the rest of the form still saves.
        assertThat(sent.getValue()).containsEntry("nickname", "Alice").containsEntry("id", 7L);
    }

    @Test
    void updateOneAndFetch_doesNotWriteTheDerivedLock_butKeepsEveryOtherKey() {
        rosterSeesTheRow();
        when(modelService.updateOneAndFetch(eq("UserAccount"), any(), any())).thenReturn(new HashMap<>());
        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);

        try (MockedStatic<IdUtils> ignored = Mockito.mockStatic(IdUtils.class)) {
            inTenant(9L, () -> controller.updateOneAndFetch(rowWithLock(7L)));
        }

        verify(modelService).updateOneAndFetch(eq("UserAccount"), sent.capture(), any());
        assertThat(sent.getValue()).doesNotContainKey("locked");
        assertThat(sent.getValue()).containsEntry("nickname", "Alice").containsEntry("id", 7L);
    }

    @Test
    void createOne_neverForwardsACallerSuppliedLock() {
        // The create endpoints do not pass the row to the write pipeline at all — inviteFromRow
        // reads named keys and builds the account itself. This pins that: only the policyId patch
        // reaches modelService, and it carries id + policyId, nothing the caller smuggled in.
        UserAccountService accountService = mock(UserAccountService.class);
        UserInfo created = new UserInfo();
        created.setUserId(7L);
        when(accountService.registerInvitedUser(any(), any(), any())).thenReturn(created);
        ReflectionTestUtils.setField(controller, "service", accountService);

        Map<String, Object> row = rowWithLock(null);
        row.put("email", "alice@example.com");
        row.put("policyId", 3L);
        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);

        inTenant(9L, () -> controller.createOne(row));

        verify(modelService).updateOne(eq("UserAccount"), sent.capture());
        assertThat(sent.getValue()).doesNotContainKey("locked")
                .containsOnlyKeys("id", "policyId");
    }

    private static void inTenant(Long tenantId, Runnable action) {
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        ContextHolder.runWith(ctx, action);
    }

    // ─── the roster's cross-tenant window ───

    /**
     * The super-admin's account list spans tenants by definition (every tenant's admins, plus its own
     * tenant's users), so these reads run inside a deliberate cross-tenant window. It has to be
     * exactly that narrow: opened only for the super-admin, and only around this read. The check is
     * on the context the search observes, because that is what the ORM consults when it decides
     * whether to append {@code tenant_id = ?}.
     */
    private Boolean crossTenantSeenBySearch(Set<String> roleCodes) {
        AtomicReference<Boolean> seen = new AtomicReference<>();
        when(modelService.searchList(eq("UserAccount"), any())).thenAnswer(inv -> {
            seen.set(ContextHolder.getContext().isCrossTenant());
            return List.of();
        });
        Context ctx = new Context();
        ctx.setTenantId(9L);
        ctx.setRoleCodes(roleCodes);
        ContextHolder.runWith(ctx, () -> controller.searchList(null));
        return seen.get();
    }

    @Test
    void searchList_superAdmin_readsInsideACrossTenantWindow() {
        installRosterScope(roleServiceReturningNoAdminRoles());

        assertThat(crossTenantSeenBySearch(Set.of(RoleConstant.CODE_SUPER_ADMIN))).isTrue();
    }

    @Test
    void searchList_tenantAdmin_staysTenantIsolated() {
        // A TENANT_ADMIN is the whole point of the distinction: it administers users, but only its
        // own tenant's. If the window keyed off "is an admin" rather than "is THE platform admin",
        // this is the test that would fail.
        assertThat(crossTenantSeenBySearch(Set.of(RoleConstant.CODE_TENANT_ADMIN))).isFalse();
    }

    @Test
    void searchList_ordinaryUser_staysTenantIsolated() {
        assertThat(crossTenantSeenBySearch(Set.of("HR"))).isFalse();
    }

    @Test
    void searchList_leavesTheOuterContextUntouched() {
        // The window is scoped to the read. A leak would hand the rest of the request — including any
        // write — an un-isolated context.
        installRosterScope(roleServiceReturningNoAdminRoles());
        when(modelService.searchList(eq("UserAccount"), any())).thenReturn(List.of());

        Context ctx = new Context();
        ctx.setTenantId(9L);
        ctx.setRoleCodes(Set.of(RoleConstant.CODE_SUPER_ADMIN));
        ContextHolder.runWith(ctx, () -> controller.searchList(null));

        assertThat(ctx.isCrossTenant()).isFalse();
    }

    /** Install a UserRosterScope backed by the given RoleService — the roster resolution the
     *  controller used to do itself. */
    private void installRosterScope(RoleService roleService) {
        ReflectionTestUtils.setField(controller, "rosterScope",
                new UserRosterScope(roleService, mock(UserRoleRelService.class)));
    }

    private static RoleService roleServiceReturningNoAdminRoles() {
        RoleService roleService = mock(RoleService.class);
        when(roleService.searchList(any(Filters.class))).thenReturn(List.of());
        return roleService;
    }

    // ─── the derived password lock on the roster ───

    /**
     * The lock is the PERSON's ({@code UserIdentity.passwordLockedUntil}) and the row reports it as
     * a SECOND AXIS next to its status — so the list has to derive it per row, and cheaply.
     */
    private static Map<String, Object> accountRow(Long accountId, Object profileId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", accountId);
        row.put("profileId", profileId);
        row.put("status", "Active");
        return row;
    }

    private List<Map<String, Object>> searchListAsHr(List<Map<String, Object>> stored) {
        when(modelService.searchList(eq("UserAccount"), any())).thenReturn(stored);
        Context ctx = new Context();
        ctx.setTenantId(9L);
        ctx.setRoleCodes(Set.of("HR"));
        return ContextHolder.callWith(ctx, () -> controller.searchList(null).getData());
    }

    @Test
    void searchList_badgesTheLockOfTheRowsPerson_inOneQuery() {
        // Two memberships, two people: A is locked, B is not. The status stays whatever it was —
        // the lock is an extra axis, not a status value.
        UserIdentityService identityService = mock(UserIdentityService.class);
        ReflectionTestUtils.setField(controller, "identityService", identityService);
        when(identityService.findPasswordLockedProfiles(any())).thenReturn(Set.of(1L));

        List<Map<String, Object>> rows = searchListAsHr(List.of(
                accountRow(100L, 1L), accountRow(200L, 2L)));

        assertThat(rows.get(0)).containsEntry("locked", true).containsEntry("status", "Active");
        assertThat(rows.get(1)).containsEntry("locked", false);
        // The whole page's people are resolved together. Per-row lookups would put one credential
        // query on the roster read for every account listed.
        verify(identityService, times(1)).findPasswordLockedProfiles(Set.of(1L, 2L));
    }

    @Test
    void searchList_aRowWithNoPerson_isNotLocked() {
        // A membership not yet paired with a person cannot carry a person's lock. False, not null:
        // the badge reads a boolean.
        UserIdentityService identityService = mock(UserIdentityService.class);
        ReflectionTestUtils.setField(controller, "identityService", identityService);
        when(identityService.findPasswordLockedProfiles(any())).thenReturn(Set.of());

        List<Map<String, Object>> rows = searchListAsHr(List.of(accountRow(100L, null)));

        assertThat(rows.get(0)).containsEntry("locked", false);
    }

    @Test
    void searchList_readsThePersonIdInWhateverShapeTheRowCarriesIt() {
        // REFERENCE conversion renders profileId as {id, displayName}, and ids reach a browser as
        // strings (a 19-digit long loses precision in JS). Read from either and the badge is right;
        // read from neither and every row silently reports "not locked".
        UserIdentityService identityService = mock(UserIdentityService.class);
        ReflectionTestUtils.setField(controller, "identityService", identityService);
        when(identityService.findPasswordLockedProfiles(any())).thenReturn(Set.of(1L, 2L));

        List<Map<String, Object>> rows = searchListAsHr(List.of(
                accountRow(100L, "1"), accountRow(200L, Map.of("id", 2L))));

        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("locked", true));
    }

    // ─── saveMyAccount ───

    @Test
    void saveMyAccount_writesOnlyTheNickname() {
        // Work contacts are the employee record's, and the login contact changes through a verified
        // flow on UserProfile. A self-service save that copied email/mobile from the body would let
        // any signed-in person move where their invitations and notices are delivered, unverified,
        // and straight into uk_user_account_tenant_email.
        UserAccountService accountService = mock(UserAccountService.class);
        ReflectionTestUtils.setField(controller, "service", accountService);
        UserAccount stored = new UserAccount();
        stored.setId(42L);
        stored.setNickname("Old Name");
        stored.setEmail("alice@acme.com");
        stored.setMobile("+6591234567");
        when(accountService.getById(42L)).thenReturn(java.util.Optional.of(stored));
        when(accountService.updateOne(any(UserAccount.class))).thenReturn(true);

        UserAccountDTO body = new UserAccountDTO();
        body.setNickname("New Name");
        body.setEmail("attacker@evil.example");
        body.setMobile("+6500000000");

        Context ctx = new Context();
        ctx.setUserId(42L);
        ctx.setTenantId(9L);
        AtomicReference<ApiResponse<Void>> response = new AtomicReference<>();
        ContextHolder.runWith(ctx, () -> response.set(controller.saveMyAccount(body)));

        assertThat(response.get().isSuccess()).isTrue();
        ArgumentCaptor<UserAccount> written = ArgumentCaptor.forClass(UserAccount.class);
        verify(accountService).updateOne(written.capture());
        assertThat(written.getValue().getNickname()).isEqualTo("New Name");
        assertThat(written.getValue().getEmail()).isEqualTo("alice@acme.com");
        assertThat(written.getValue().getMobile()).isEqualTo("+6591234567");
    }

    // ─── @CrossTenant operations are bounded to the caller's own tenant ───

    /**
     * rehire / resetWorkContacts / unbindAndReinvite are {@code @CrossTenant} on the service so the
     * platform super-admin can reach a roster row in another company. The annotation waives the
     * ORM's tenant filter for every caller, and onRosterAccounts bounds only the super-admin — so a
     * tenant HR holding the grant could post another tenant's account id. The service mock here
     * returns the row whatever the caller's tenant, exactly as the un-filtered load does.
     */
    private UserAccountService accountServiceHolding(Long accountId, Long tenantId) {
        UserAccountService accountService = mock(UserAccountService.class);
        UserAccount row = new UserAccount();
        row.setId(accountId);
        row.setTenantId(tenantId);
        when(accountService.getById(accountId)).thenReturn(java.util.Optional.of(row));
        ReflectionTestUtils.setField(controller, "service", accountService);
        return accountService;
    }

    private static void asCallerIn(Long tenantId, Set<String> roleCodes, Runnable action) {
        Context ctx = new Context();
        ctx.setUserId(1L);
        ctx.setTenantId(tenantId);
        ctx.setRoleCodes(roleCodes);
        ContextHolder.runWith(ctx, action);
    }

    @Test
    void rehire_byATenantHR_onAnotherTenantsRow_isRefused_andTheServiceIsNeverInvoked() {
        UserAccountService accountService = accountServiceHolding(7L, 9L);

        assertThatThrownBy(() -> asCallerIn(2L, Set.of("HR"), () -> controller.rehire(7L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("User not found.");   // same answer as a nonexistent id: no existence leak

        verify(accountService, never()).rehire(any());
    }

    @Test
    void resetWorkContacts_byATenantHR_onAnotherTenantsRow_isRefused() {
        UserAccountService accountService = accountServiceHolding(7L, 9L);
        ResetWorkContactsDTO dto = new ResetWorkContactsDTO();
        dto.setReason("moved");

        assertThatThrownBy(() -> asCallerIn(2L, Set.of("HR"), () -> controller.resetWorkContacts(7L, dto)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("User not found.");

        verify(accountService, never()).resetWorkContacts(any(), any());
    }

    @Test
    void unbindAndReinvite_byATenantHR_onAnotherTenantsRow_isRefused() {
        accountServiceHolding(7L, 9L);
        UserInvitationService invitationService = mock(UserInvitationService.class);
        ReflectionTestUtils.setField(controller, "invitationService", invitationService);
        UnbindAndReinviteDTO dto = new UnbindAndReinviteDTO();
        dto.setReason("wrong person");

        assertThatThrownBy(() -> asCallerIn(2L, Set.of("HR"), () -> controller.unbindAndReinvite(7L, dto)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("User not found.");

        verify(invitationService, never()).unbindAndReinvite(any(), any(), any());
    }

    @Test
    void rehire_byATenantHR_onItsOwnTenantsRow_reachesTheService() {
        rosterSeesTheRow();
        UserAccountService accountService = accountServiceHolding(7L, 2L);

        asCallerIn(2L, Set.of("HR"), () -> controller.rehire(7L));

        verify(accountService).rehire(7L);
    }

    @Test
    void rehire_byThePlatformSuperAdmin_onARosterRowInAnotherTenant_reachesTheService() {
        // The case the annotation exists for: the super-admin works a roster that spans tenants, and
        // the roster check (not the caller's tenant) is what bounds it.
        UserAccountService accountService = accountServiceHolding(7L, 9L);
        installRosterScope(roleServiceReturningNoAdminRoles());
        when(modelService.count(eq("UserAccount"), any())).thenReturn(1L);

        asCallerIn(2L, Set.of(RoleConstant.CODE_SUPER_ADMIN), () -> controller.rehire(7L));

        verify(accountService).rehire(7L);
    }

    // ─── the lock on a read that names its own fields ───

    /**
     * The roster table does not send "give me everything": it sends the field list built from its
     * declared columns, and profileId is not among them. The ORM only auto-adds id / version /
     * sliceId to a caller-supplied set, so the read came back with no person on any row and the
     * lock badge was stamped false for the whole page — on every real request, while the
     * hand-built rows above (which carry profileId already) stayed green.
     *
     * <p>Stubbed the way the ORM actually answers: profileId appears in the row only if the query
     * asked for it.
     */
    private List<Map<String, Object>> searchListWithFields(List<String> fields, Map<Long, Long> peopleByAccount) {
        when(modelService.searchList(eq("UserAccount"), any())).thenAnswer(invocation -> {
            FlexQuery query = invocation.getArgument(1);
            List<String> asked = query.getFields();
            return peopleByAccount.entrySet().stream().map(entry -> {
                Map<String, Object> row = new HashMap<>();
                row.put("id", entry.getKey());
                row.put("status", "Active");
                if (asked == null || asked.isEmpty() || asked.contains("profileId")) {
                    row.put("profileId", entry.getValue());
                }
                return row;
            }).toList();
        });
        SearchListParams params = new SearchListParams();
        params.setFields(fields);
        Context ctx = new Context();
        ctx.setTenantId(9L);
        ctx.setRoleCodes(Set.of("HR"));
        return ContextHolder.callWith(ctx, () -> controller.searchList(params).getData());
    }

    @Test
    void searchList_withAnExplicitFieldList_stillBadgesTheLock_andHandsBackNoProfileId() {
        UserIdentityService identityService = mock(UserIdentityService.class);
        ReflectionTestUtils.setField(controller, "identityService", identityService);
        when(identityService.findPasswordLockedProfiles(any())).thenReturn(Set.of(1L));

        // The shape the production caller sends: an explicit list built from the table's columns.
        Map<Long, Long> peopleByAccount = new LinkedHashMap<>();
        peopleByAccount.put(100L, 1L);
        peopleByAccount.put(200L, 2L);
        List<Map<String, Object>> rows = searchListWithFields(List.of("status"), peopleByAccount);

        // Load-bearing: without borrowing profileId for the read, both rows report false here.
        assertThat(rows.get(0)).containsEntry("locked", true);
        assertThat(rows.get(1)).containsEntry("locked", false);
        verify(identityService, times(1)).findPasswordLockedProfiles(Set.of(1L, 2L));
        // Borrowed, not granted: the caller asked for status, so the person id goes back out again.
        assertThat(rows).allSatisfy(row -> assertThat(row).doesNotContainKey("profileId"));
    }

    @Test
    void searchList_whenTheCallerAskedForProfileId_itStaysInTheRow() {
        // Only a BORROWED key is removed. Stripping one the caller named would break the column
        // they asked to display.
        UserIdentityService identityService = mock(UserIdentityService.class);
        ReflectionTestUtils.setField(controller, "identityService", identityService);
        when(identityService.findPasswordLockedProfiles(any())).thenReturn(Set.of(1L));

        List<Map<String, Object>> rows =
                searchListWithFields(List.of("status", "profileId"), Map.of(100L, 1L));

        assertThat(rows.get(0)).containsEntry("locked", true).containsEntry("profileId", 1L);
    }

    @Test
    void searchList_withNoFieldList_isLeftAlone() {
        // An empty/absent set already means every field — widening it would be a no-op that only
        // risked turning "all fields" into "these two".
        UserIdentityService identityService = mock(UserIdentityService.class);
        ReflectionTestUtils.setField(controller, "identityService", identityService);
        when(identityService.findPasswordLockedProfiles(any())).thenReturn(Set.of(1L));

        List<Map<String, Object>> rows = searchListWithFields(null, Map.of(100L, 1L));

        assertThat(rows.get(0)).containsEntry("locked", true).containsEntry("profileId", 1L);
    }

    @Test
    void searchList_anAggregateQuery_isPassedThroughUntouched() {
        // The shape a chart sends: "count the accounts per status" — a field list plus groupBy,
        // which is what flips FlexQuery.aggregate. Borrowing profileId into THAT selection is not a
        // widening: the column would have to join the GROUP BY (one group per person, so the counts
        // stop being per status) or be wrapped in an aggregate function. Either way the caller gets
        // back a different result than it asked for.
        UserIdentityService identityService = mock(UserIdentityService.class);
        ReflectionTestUtils.setField(controller, "identityService", identityService);
        AtomicReference<List<String>> asked = new AtomicReference<>();
        when(modelService.searchList(eq("UserAccount"), any())).thenAnswer(invocation -> {
            FlexQuery query = invocation.getArgument(1);
            asked.set(query.getFields());
            // A mutable row: the guard is what must keep the stamping away, not an
            // UnsupportedOperationException from an immutable map.
            Map<String, Object> grouped = new HashMap<>();
            grouped.put("status", "Active");
            grouped.put("count", 3L);
            return List.of(grouped);
        });

        SearchListParams params = new SearchListParams();
        params.setFields(List.of("status"));
        params.setGroupBy(List.of("status"));
        Context ctx = new Context();
        ctx.setTenantId(9L);
        ctx.setRoleCodes(Set.of("HR"));
        List<Map<String, Object>> rows =
                ContextHolder.callWith(ctx, () -> controller.searchList(params).getData());

        // Load-bearing: the field set reaches the ORM exactly as the caller sent it.
        assertThat(asked.get()).containsExactly("status");
        // A grouped row is not an account, so there is nothing to badge and nobody to ask about.
        verify(identityService, never()).findPasswordLockedProfiles(any());
        assertThat(rows.get(0)).doesNotContainKey("locked").doesNotContainKey("profileId");
    }

    @Test
    void getById_withAnExplicitFieldList_stillBadgesTheLock_andHandsBackNoProfileId() {
        rosterSeesTheRow();
        UserIdentityService identityService = mock(UserIdentityService.class);
        ReflectionTestUtils.setField(controller, "identityService", identityService);
        when(identityService.findPasswordLockedProfiles(any())).thenReturn(Set.of(1L));
        when(modelService.getById(eq("UserAccount"), any(), any(), any(), any())).thenAnswer(invocation -> {
            List<String> asked = invocation.getArgument(2);
            Map<String, Object> row = new HashMap<>();
            row.put("id", 100L);
            row.put("status", "Active");
            if (asked != null && asked.contains("profileId")) {
                row.put("profileId", 1L);
            }
            return Optional.of(row);
        });

        GetByIdParams params = new GetByIdParams();
        params.setId(100L);
        params.setFields(List.of("status"));
        Context ctx = new Context();
        ctx.setTenantId(9L);
        ctx.setRoleCodes(Set.of("HR"));
        Map<String, Object> row;
        try (MockedStatic<IdUtils> ids = Mockito.mockStatic(IdUtils.class)) {
            ids.when(() -> IdUtils.formatId(eq("UserAccount"), any())).thenReturn(100L);
            row = ContextHolder.callWith(ctx, () -> controller.getById(params).getData());
        }

        assertThat(row).containsEntry("locked", true).doesNotContainKey("profileId");
    }
}
