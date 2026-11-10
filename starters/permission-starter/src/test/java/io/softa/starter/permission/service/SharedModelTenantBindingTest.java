package io.softa.starter.permission.service;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.SharedModelTenantBinding;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A shared model bound to its tenants keeps, inside a customer tenant, only the rows that tenant's
 * records point at — for every caller of the tenant, its admin included.
 */
class SharedModelTenantBindingTest {

    private static final Long TENANT = 7L;

    private PermissionInfo pi;
    private PermissionServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pi = new PermissionInfo();
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(any(), anyLong())).thenAnswer(inv -> pi);
        ModelService<Serializable> models = mock(ModelService.class);
        when(models.getRelatedIds(eq("UserAccount"), any(Filters.class), eq("profileId")))
                .thenReturn(List.of(11L, 12L));
        when(models.getRelatedIds(eq("UserAccount"), any(Filters.class), eq("formerProfileId")))
                .thenReturn(List.of(13L));
        service = new PermissionServiceImpl(provider, mock(ScopeRuleCompiler.class), null, models,
                mock(ScopeApplicabilityResolver.class), () -> null);
        service.setTenantBindings(List.of(
                new SharedModelTenantBinding("UserProfile", "UserAccount", List.of("profileId", "formerProfileId"))));
    }

    private Filters read(Long tenantId, String model, boolean ignoringRowScope) {
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        ctx.setUserId(2L);
        return ContextHolder.callWith(ctx, () -> ignoringRowScope
                ? service.appendScopeAccessFiltersIgnoringRowScope(model, null)
                : service.appendScopeAccessFilters(model, null));
    }

    @Test
    @DisplayName("a tenant admin reads only the profiles of its own tenant's accounts")
    void aTenantAdminIsBound() {
        pi.setRoleCodes(Set.of(PermissionInfo.CODE_TENANT_ADMIN));

        assertThat(String.valueOf(read(TENANT, "UserProfile", false))).isEqualTo("[\"id\",\"IN\",[11,12,13]]");
        assertThat(String.valueOf(read(TENANT, "UserProfile", true))).isEqualTo("[\"id\",\"IN\",[11,12,13]]");
    }

    @Test
    @DisplayName("an unbound model is left alone")
    void anUnboundModelIsLeftAlone() {
        pi.setRoleCodes(Set.of(PermissionInfo.CODE_TENANT_ADMIN));

        assertThat(read(TENANT, "Currency", false)).isNull();
    }

    @Test
    @DisplayName("the platform tenant and the platform administrator keep the whole table")
    void thePlatformKeepsEverything() {
        pi.setRoleCodes(Set.of(PermissionInfo.CODE_TENANT_ADMIN));
        assertThat(read(BaseConstant.PLATFORM_TENANT_ID, "UserProfile", false)).isNull();

        pi.setRoleCodes(Set.of(PermissionInfo.CODE_SUPER_ADMIN));
        assertThat(read(TENANT, "UserProfile", false)).isNull();
    }

    @Test
    @DisplayName("system work keeps the whole table")
    void systemWorkIsNotBound() {
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        ctx.setUserId(2L);
        ctx.setSkipPermissionCheck(true);

        assertThat(ContextHolder.callWith(ctx, () -> service.appendScopeAccessFilters("UserProfile", null))).isNull();
    }
}
