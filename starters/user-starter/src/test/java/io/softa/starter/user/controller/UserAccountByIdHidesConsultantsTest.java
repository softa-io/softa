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
 * A consultant's membership is invisible to a tenant's by-id operations, not only to its lists.
 *
 * <p>The roster scope hid consultant rows from searchPage / searchList; getById, freeze and the
 * generic fall-through endpoints resolved the row through the ORM's tenant filter alone, which a
 * consultant's membership in that tenant passes. So the page said "no such member" while the by-id
 * surface froze, re-roled, unmasked or deleted them — and the id is printed beside every change a
 * consultant makes, in the tenant's own audit panel.
 *
 * <p>Driven through {@code modelService.count}: every by-id path now asks it with the roster
 * predicate, and what is pinned is (a) that the predicate carries the consultant exclusion and (b)
 * that a short count refuses BEFORE the operation runs. The refusal is the same "User not found." a
 * nonexistent id gets, so it confirms nothing about the id it hides.
 */
class UserAccountByIdHidesConsultantsTest {

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

    /** What the roster count answers for the ids being acted on. */
    private void rosterSees(long count) {
        when(modelService.count(eq("UserAccount"), any(Filters.class))).thenReturn(count);
    }

    private static FreezeAccountDTO reason() {
        FreezeAccountDTO dto = new FreezeAccountDTO();
        dto.setReason("test");
        return dto;
    }

    // ─── the predicate ───

    @Test
    void theRosterCountCarriesTheConsultantExclusion() {
        rosterSees(1);
        asTenantAdmin(() -> {
            controller.freezeAccount(CONSULTANT_ROW, reason());
            return null;
        });

        ArgumentCaptor<Filters> asked = ArgumentCaptor.forClass(Filters.class);
        verify(modelService).count(eq("UserAccount"), asked.capture());
        assertThat(asked.getValue().toString().toLowerCase()).contains("consultant");
    }

    // ─── refused before the operation runs ───

    @Test
    void freezingAHiddenRowIsRefusedAsIfItDidNotExist() {
        rosterSees(0);
        asTenantAdmin(() -> {
            assertThatThrownBy(() -> controller.freezeAccount(CONSULTANT_ROW, reason()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("User not found.");
            return null;
        });
        verify(service, never()).freezeAccount(anyLong(), any());
    }

    @Test
    void readingAHiddenRowByIdAnswersNothing() {
        rosterSees(0);
        GetByIdParams params = new GetByIdParams();
        params.setId(CONSULTANT_ROW);

        Map<String, Object> row = withIds(() -> asTenantAdmin(() -> controller.getById(params).getData()));

        assertThat(row).isNull();
        verify(modelService, never()).getById(eq("UserAccount"), any(), any(), any(), any());
    }

    @Test
    void deletingAHiddenRowIsRefused() {
        rosterSees(0);
        withIds(() -> asTenantAdmin(() -> {
            assertThatThrownBy(() -> controller.deleteByIds(List.of(CONSULTANT_ROW)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("User not found.");
            return null;
        }));
        verify(modelService, never()).deleteByIds(eq("UserAccount"), any());
    }

    @Test
    void updatingAHiddenRowIsRefused() {
        rosterSees(0);
        Map<String, Object> row = new HashMap<>(Map.of("id", CONSULTANT_ROW, "status", "FROZEN"));
        withIds(() -> asTenantAdmin(() -> {
            assertThatThrownBy(() -> controller.updateOne(row))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("User not found.");
            return null;
        }));
        verify(modelService, never()).updateOne(eq("UserAccount"), anyMap());
    }

    // ─── the paired negatives: a visible row still works ───

    @Test
    void freezingAVisibleRowStillRuns() {
        rosterSees(1);
        asTenantAdmin(() -> {
            controller.freezeAccount(CONSULTANT_ROW, reason());
            return null;
        });
        verify(service).freezeAccount(CONSULTANT_ROW, "test");
    }

    @Test
    void readingAVisibleRowStillAnswers() {
        rosterSees(1);
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
        // an employee into a row nobody in the tenant can find again, or a consultant into a visible
        // employment they can then freeze — so it is dropped, silently, like the derived lock.
        rosterSees(1);
        when(modelService.updateOne(eq("UserAccount"), anyMap())).thenReturn(true);
        Map<String, Object> row = new HashMap<>(Map.of("id", CONSULTANT_ROW, "consultant", true, "nickname", "x"));

        withIds(() -> asTenantAdmin(() -> controller.updateOne(row)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> written = ArgumentCaptor.forClass(Map.class);
        verify(modelService).updateOne(eq("UserAccount"), written.capture());
        assertThat(written.getValue()).doesNotContainKey("consultant").containsKey("nickname");
    }
}
