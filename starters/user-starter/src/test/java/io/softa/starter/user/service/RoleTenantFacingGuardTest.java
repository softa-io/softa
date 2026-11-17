package io.softa.starter.user.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.EntitlementService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.RoleNavigation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A role saved inside a customer tenant grants only that tenant's own, entitled pages. */
class RoleTenantFacingGuardTest {

    private static final Long TENANT = 7L;

    private static final Long ROLE = 42L;

    private RoleTenantFacingGuard guard;

    @BeforeAll
    static void ensureSystemConfig() {
        // A refusal is a BaseException, whose message goes through I18n, which reads SystemConfig.
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ModelService<Long> models = mock(ModelService.class);
        // Both reads answer only past the caller's scope, as they do for a role manager without the
        // admin bypass: the catalog is shared and anchors to no tenant.
        when(models.searchList(eq("Permission"), any(FlexQuery.class))).thenAnswer(inv -> unscoped() ? List.of(
                Map.of("id", "permission.employee.view", "navigationId", "navigation.corehr.employee"),
                Map.of("id", "permission.payroll.run", "navigationId", "navigation.payroll.run"),
                Map.of("id", "permission.system.tenant-info.view", "navigationId",
                        "navigation.system.tenant-data.tenant-info")) : List.of());
        // The stored role: it already holds a platform page, saved before the check existed.
        RoleNavigation held = new RoleNavigation();
        held.setRoleId(ROLE);
        held.setNavigationId("navigation.system.tenant-data.tenant-info");
        held.setPermissionIds(JsonUtils.stringToObject("[\"permission.system.tenant-info.view\"]", JsonNode.class));
        RoleNavigationService stored = mock(RoleNavigationService.class);
        when(stored.searchList(any(Filters.class))).thenAnswer(inv -> unscoped() ? List.of(held) : List.of());
        guard = new RoleTenantFacingGuard(models, stored);
        ReflectionTestUtils.setField(guard, "platformNavPrefixesCsv", "navigation.system.,navigation.studio.");
        EntitlementService entitlement = mock(EntitlementService.class);
        when(entitlement.entitledModules(anyLong())).thenReturn(Set.of("corehr"));
        ReflectionTestUtils.setField(guard, "entitlementService", entitlement);
    }

    private static boolean unscoped() {
        Context ctx = ContextHolder.getContext();
        return ctx != null && ctx.isSkipPermissionCheck();
    }

    private static JsonNode rows(String json) {
        return JsonUtils.stringToObject(json, JsonNode.class);
    }

    private void saveIn(Long tenantId, boolean skipPermissionCheck, JsonNode navigations) {
        saveIn(tenantId, skipPermissionCheck, null, navigations);
    }

    private void saveIn(Long tenantId, boolean skipPermissionCheck, Long roleId, JsonNode navigations) {
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        ctx.setUserId(2L);
        ctx.setSkipPermissionCheck(skipPermissionCheck);
        ContextHolder.callWith(ctx, () -> {
            guard.assertTenantFacing(roleId, navigations);
            return null;
        });
    }

    @Test
    void theTenantsOwnEntitledPagesAreSaved() {
        assertThatCode(() -> saveIn(TENANT, false, rows("""
                [{"navigationId":"navigation.corehr.employee","permissionIds":["permission.employee.view"]}]
                """))).doesNotThrowAnyException();
    }

    @Test
    void aPlatformPageIsRefused() {
        assertThatThrownBy(() -> saveIn(TENANT, false, rows("""
                [{"navigationId":"navigation.system.tenant-data.tenant-info",
                  "permissionIds":["permission.system.tenant-info.view"]}]
                """))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("navigation.system.tenant-data.tenant-info")
                // The message goes through MessageFormat: a bare apostrophe would swallow itself.
                .hasMessageContaining("the tenant's plan");
    }

    /** Filed under a page of the tenant's, a platform permission is still the platform's. */
    @Test
    void aPlatformPermissionUnderATenantPageIsRefused() {
        assertThatThrownBy(() -> saveIn(TENANT, false, rows("""
                [{"navigationId":"navigation.corehr.employee",
                  "permissionIds":["permission.employee.view","permission.system.tenant-info.view"]}]
                """))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("permission.system.tenant-info.view");
    }

    @Test
    void aModuleThePlanDoesNotIncludeIsRefused() {
        assertThatThrownBy(() -> saveIn(TENANT, false, rows("""
                [{"navigationId":"navigation.payroll.run","permissionIds":["permission.payroll.run"]}]
                """))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("navigation.payroll.run");
    }

    /** A permission since removed from the catalog names no endpoint; its row's page decides. */
    @Test
    void anUnknownPermissionIsJudgedByItsRow() {
        assertThatCode(() -> saveIn(TENANT, false, rows("""
                [{"navigationId":"navigation.corehr.employee","permissionIds":["permission.since.removed"]}]
                """))).doesNotThrowAnyException();
        assertThatThrownBy(() -> saveIn(TENANT, false, rows("""
                [{"navigationId":"navigation.system.tenant-data.tenant-info","permissionIds":["permission.since.removed"]}]
                """))).isInstanceOf(BusinessException.class);
    }

    /**
     * A role saved before the check may already hold a platform page the wizard cannot show, and sends it
     * back with every save; refusing it would leave the role uneditable. What it already holds passes.
     */
    @Test
    void whatTheRoleAlreadyHoldsIsLetThrough() {
        assertThatCode(() -> saveIn(TENANT, false, ROLE, rows("""
                [{"navigationId":"navigation.corehr.employee","permissionIds":["permission.employee.view"]},
                 {"navigationId":"navigation.system.tenant-data.tenant-info",
                  "permissionIds":["permission.system.tenant-info.view"]}]
                """))).doesNotThrowAnyException();
    }

    /** Only what the role already holds passes: a platform page the save adds is refused all the same. */
    @Test
    void aPlatformPageTheSaveAddsIsStillRefused() {
        assertThatThrownBy(() -> saveIn(TENANT, false, ROLE, rows("""
                [{"navigationId":"navigation.system.tenant-data.tenant-info",
                  "permissionIds":["permission.system.tenant-info.view","permission.system.tenant-info.update"]},
                 {"navigationId":"navigation.payroll.run","permissionIds":["permission.payroll.run"]}]
                """))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("permission.system.tenant-info.update")
                .hasMessageContaining("navigation.payroll.run")
                .hasMessageNotContaining("permission.system.tenant-info.view");
    }

    /** A new role holds nothing yet, so a platform page in it is refused. */
    @Test
    void aNewRoleHoldsNothing() {
        assertThatThrownBy(() -> saveIn(TENANT, false, null, rows("""
                [{"navigationId":"navigation.system.tenant-data.tenant-info",
                  "permissionIds":["permission.system.tenant-info.view"]}]
                """))).isInstanceOf(BusinessException.class);
    }

    @Test
    void thePlatformTenantAndSystemWritesAreLeftAlone() {
        JsonNode platform = rows("""
                [{"navigationId":"navigation.system.tenant-data.tenant-info",
                  "permissionIds":["permission.system.tenant-info.view"]}]
                """);
        assertThatCode(() -> saveIn(BaseConstant.PLATFORM_TENANT_ID, false, platform)).doesNotThrowAnyException();
        assertThatCode(() -> saveIn(TENANT, true, platform)).doesNotThrowAnyException();
        assertThatCode(() -> saveIn(null, false, platform)).doesNotThrowAnyException();
    }
}
