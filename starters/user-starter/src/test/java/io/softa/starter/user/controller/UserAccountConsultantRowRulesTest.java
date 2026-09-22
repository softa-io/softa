package io.softa.starter.user.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.web.dto.GetByIdParams;
import io.softa.starter.user.dto.FreezeAccountDTO;
import io.softa.starter.user.service.PermissionCacheInvalidator;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserRoleRelService;
import io.softa.starter.user.service.UserRosterScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a tenant may and may not do to a consultant's membership.
 *
 * <p>A consultant IS on the customer's roster, and deliberately: the customer may suspend one
 * without going through the platform, which they cannot do to a row they cannot see. That is a
 * veto, not ownership — entry needs the platform's grant AND this account's status, and each side
 * can say no on its own.
 *
 * <p>So the line is not visibility, it is which operations are the tenant's. Suspending is; editing
 * the row, re-roling it and deleting it are not — a consultant's reach comes from the subscription
 * rather than from any role, the contacts on the row are PLATFORM login identifiers, and the
 * account is the actor the tenant's own audit log points at.
 *
 * <p>Every by-id path resolves the row first and refuses before the operation runs. Two different
 * refusals, on purpose: another tenant's row is "User not found." (confirming nothing about an id
 * it hides), while a consultant in THIS tenant is told plainly whose account it is — the page just
 * listed it, so pretending it does not exist would only puzzle the operator.
 */
class UserAccountConsultantRowRulesTest {

    private static final long TENANT = 2L;
    private static final long ADMIN = 1L;
    private static final long CONSULTANT_ROW = 999L;

    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);
    private final UserAccountService service = mock(UserAccountService.class);
    private final UserAccountController controller = new UserAccountController();

    @BeforeEach
    void wire() {
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;

        ReflectionTestUtils.setField(controller, "modelService", modelService);
        ReflectionTestUtils.setField(controller, "service", service);
        ReflectionTestUtils.setField(controller, "permissionCacheInvalidator", mock(PermissionCacheInvalidator.class));
        // getById stamps the derived lock badge after a hit; the badge is not what these cases are about.
        UserIdentityService identityService = mock(UserIdentityService.class);
        when(identityService.findPasswordLockedProfiles(any())).thenReturn(Set.of());
        ReflectionTestUtils.setField(controller, "identityService", identityService);
        ReflectionTestUtils.setField(controller, "rosterScope",
                new UserRosterScope(mock(RoleService.class), mock(UserRoleRelService.class)));
    }

    @AfterEach
    void tearDown() {
        SystemConfig.env = null;
    }

    /** An ordinary tenant admin — no SUPER_ADMIN code, so the roster window is never opened. */
    private static <T> T asTenantAdmin(java.util.function.Supplier<T> body) {
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        ctx.setUserId(ADMIN);
        return ContextHolder.callWith(ctx, body::get);
    }

    /**
     * Run with the static id helpers as identity. They consult model metadata that a unit test does
     * not load; the ids here are already the model's key type, so passing them through is what the
     * real helper would do.
     */
    private static <T> T withIds(java.util.function.Supplier<T> body) {
        try (MockedStatic<IdUtils> ids = Mockito.mockStatic(IdUtils.class)) {
            ids.when(() -> IdUtils.formatId(eq("UserAccount"), any())).thenAnswer(inv -> inv.getArgument(1));
            ids.when(() -> IdUtils.formatIds(eq("UserAccount"), any())).thenAnswer(inv -> inv.getArgument(1));
            return body.get();
        }
    }

    /** What the roster read answers for the ids being acted on. */
    private void rosterSees(Map<String, Object>... rows) {
        when(modelService.searchList(eq("UserAccount"), any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of(rows));
        // getById keeps its own count-based visibility check — it reads one row and has no second
        // question to ask of it.
        when(modelService.count(eq("UserAccount"), any(Filters.class))).thenReturn((long) rows.length);
    }

    private static Map<String, Object> ordinaryRow(long id) {
        return Map.of("id", id, "consultant", false);
    }

    private static Map<String, Object> consultantRow(long id) {
        return Map.of("id", id, "consultant", true);
    }

    private static FreezeAccountDTO reason() {
        FreezeAccountDTO dto = new FreezeAccountDTO();
        dto.setReason("test");
        return dto;
    }

    // ─── the one operation that IS the tenant's ───

    @Test
    void aTenantMaySuspendAConsultant() {
        // The whole reason the row is on their roster. A customer who wants a consultant out today
        // should not have to raise a ticket with the platform and wait.
        rosterSees(consultantRow(CONSULTANT_ROW));
        asTenantAdmin(() -> {
            controller.freezeAccount(CONSULTANT_ROW, reason());
            return null;
        });

        verify(service).freezeAccount(eq(CONSULTANT_ROW), any());
    }

    @Test
    void aTenantMayLiftItsOwnSuspension() {
        rosterSees(consultantRow(CONSULTANT_ROW));
        asTenantAdmin(() -> {
            controller.unfreezeAccount(CONSULTANT_ROW, reason());
            return null;
        });

        verify(service).unfreezeAccount(eq(CONSULTANT_ROW), any());
    }

    // ─── everything else on a consultant is refused, and says why ───

    @Test
    void deletingAConsultantIsRefused() {
        // It is the actor the tenant's own audit log points at. Removing it blanks the authorship of
        // every change the consultant made while they had access.
        rosterSees(consultantRow(CONSULTANT_ROW));
        withIds(() -> asTenantAdmin(() -> {
            assertThatThrownBy(() -> controller.deleteByIds(List.of(CONSULTANT_ROW)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("managed by the platform");
            return null;
        }));
        verify(modelService, never()).deleteByIds(eq("UserAccount"), any());
    }

    @Test
    void editingAConsultantIsRefused() {
        rosterSees(consultantRow(CONSULTANT_ROW));
        Map<String, Object> row = new HashMap<>(Map.of("id", CONSULTANT_ROW, "nickname", "Renamed"));
        withIds(() -> asTenantAdmin(() -> {
            assertThatThrownBy(() -> controller.updateOne(row))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("managed by the platform");
            return null;
        }));
        verify(modelService, never()).updateOne(eq("UserAccount"), anyMap());
    }

    @Test
    void theRefusalNamesTheAccountRatherThanPretendingItIsMissing() {
        // Distinct from the other-tenant refusal on purpose: the roster page just listed this row,
        // so "User not found." would only make an operator doubt the page.
        rosterSees(consultantRow(CONSULTANT_ROW));
        Map<String, Object> row = new HashMap<>(Map.of("id", CONSULTANT_ROW, "nickname", "Renamed"));
        withIds(() -> asTenantAdmin(() -> {
            assertThatThrownBy(() -> controller.updateOne(row))
                    .hasMessageContaining("suspend");
            return null;
        }));
    }

    @Test
    void anOrdinaryMemberIsUntouchedByAnyOfThis() {
        rosterSees(ordinaryRow(ADMIN));
        Map<String, Object> row = new HashMap<>(Map.of("id", ADMIN, "nickname", "Renamed"));
        when(modelService.updateOne(eq("UserAccount"), anyMap())).thenReturn(true);

        withIds(() -> asTenantAdmin(() -> controller.updateOne(row)));

        verify(modelService).updateOne(eq("UserAccount"), anyMap());
    }

    // ─── another tenant's row is still nothing at all ───

    @Test
    void anotherTenantsRowIsRefusedAsIfItDidNotExist() {
        rosterSees();   // the roster read returns nothing for this id
        asTenantAdmin(() -> {
            assertThatThrownBy(() -> controller.freezeAccount(CONSULTANT_ROW, reason()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("User not found.");
            return null;
        });
        verify(service, never()).freezeAccount(anyLong(), any());
    }

    @Test
    void readingAnotherTenantsRowByIdAnswersNothing() {
        rosterSees();
        GetByIdParams params = new GetByIdParams();
        params.setId(CONSULTANT_ROW);

        Map<String, Object> row = withIds(() -> asTenantAdmin(() -> controller.getById(params).getData()));

        assertThat(row).isNull();
        verify(modelService, never()).getById(eq("UserAccount"), any(), any(), any(), any());
    }

    // ─── the bounds the generic endpoint applied, kept by its shadow ───

    @Test
    void anOversizedIdListIsRefusedBeforeAnythingIsRead() {
        // ModelController.validateIds caps a by-id list at MAX_BATCH_SIZE; the shadow replaced it
        // with a bare notEmpty and lost the cap, so /UserAccount/deleteByIds accepted a hundred
        // thousand ids — a by-id endpoint turned into a way to walk, or empty, the table.
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 10_001).boxed().toList();
        asTenantAdmin(() -> {
            assertThatThrownBy(() -> controller.deleteByIds(tooMany))
                    .hasMessageContaining("maximum");
            return null;
        });
        verify(modelService, never()).deleteByIds(eq("UserAccount"), any());
        verify(modelService, never()).count(eq("UserAccount"), any());
    }

    @Test
    void aNullInTheIdListIsRefused() {
        // The other half of validateIds. A null element widens the IN to every row.
        List<Long> withNull = java.util.Arrays.asList(1L, null, 3L);
        asTenantAdmin(() -> {
            assertThatThrownBy(() -> controller.deleteByIds(withNull))
                    .hasMessageContaining("null");
            return null;
        });
        verify(modelService, never()).deleteByIds(eq("UserAccount"), any());
    }

    // ─── reading one is fine: that is what puts it on the roster ───

    @Test
    void aTenantReadsAConsultantRowByIdLikeAnyOther() {
        rosterSees(consultantRow(CONSULTANT_ROW));
        when(modelService.getById(eq("UserAccount"), any(), any(), any(), any()))
                // Mutable: the controller stamps the derived lock badge onto the row it hands back.
                .thenReturn(Optional.of(new HashMap<>(Map.of("id", CONSULTANT_ROW))));
        GetByIdParams params = new GetByIdParams();
        params.setId(CONSULTANT_ROW);

        Map<String, Object> row = withIds(() -> asTenantAdmin(() -> controller.getById(params).getData()));

        assertThat(row).containsEntry("id", CONSULTANT_ROW);
    }

    // ─── the flag itself is not writable from here ───

    @Test
    void anInboundWriteCannotSetOrClearTheConsultantFlag() {
        // Set once when the platform mints the membership. A tenant-side write carrying it would turn
        // an employee into a consultant the tenant may no longer edit, or a consultant into an
        // ordinary employment it may — so it is dropped, silently, like the derived lock. Asserted
        // on an ORDINARY row, because a consultant row is refused before the payload is written and
        // would pass this for the wrong reason.
        rosterSees(ordinaryRow(ADMIN));
        when(modelService.updateOne(eq("UserAccount"), anyMap())).thenReturn(true);
        Map<String, Object> row = new HashMap<>(Map.of("id", ADMIN, "consultant", true, "nickname", "x"));

        withIds(() -> asTenantAdmin(() -> controller.updateOne(row)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> written = ArgumentCaptor.forClass(Map.class);
        verify(modelService).updateOne(eq("UserAccount"), written.capture());
        assertThat(written.getValue()).doesNotContainKey("consultant").containsKey("nickname");
    }
}
