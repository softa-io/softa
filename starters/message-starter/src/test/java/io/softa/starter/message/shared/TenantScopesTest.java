package io.softa.starter.message.shared;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Who sees which rows of the tables that are not multi-tenant (the send logs): the platform every
 * row, a tenant only its own — and the platform's own pages refuse a tenant outright.
 */
class TenantScopesTest {

    @BeforeEach
    void setUp() {
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
    }

    @AfterEach
    void tearDown() {
        SystemConfig.env = null;
    }

    private static <T> T as(long tenantId, java.util.function.Supplier<T> read) {
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        return ContextHolder.callWith(ctx, read::get);
    }

    @Test
    void aTenantSeesOnlyTheRowsFiledUnderIt() {
        assertThat(as(7L, () -> TenantScopes.visibleToCurrentCaller(7L))).isTrue();
        assertThat(as(7L, () -> TenantScopes.visibleToCurrentCaller(8L))).isFalse();
        // The platform's own mail, filed under -1, is not a tenant's.
        assertThat(as(7L, () -> TenantScopes.visibleToCurrentCaller(TenantScopes.PLATFORM))).isFalse();
    }

    @Test
    void thePlatformSeesEveryRow() {
        assertThat(as(TenantScopes.PLATFORM, () -> TenantScopes.visibleToCurrentCaller(8L))).isTrue();
    }

    @Test
    void thePlatformsPagesRefuseATenant() {
        assertThrows(PermissionException.class,
                () -> as(7L, () -> {
                    TenantScopes.requirePlatformCaller("The mail send log");
                    return null;
                }));
        assertDoesNotThrow(() -> as(TenantScopes.PLATFORM, () -> {
            TenantScopes.requirePlatformCaller("The mail send log");
            return null;
        }));
    }

    @Test
    void withMultiTenancyOffEveryoneIsThePlatform() {
        SystemConfig.env = null;

        assertThat(as(7L, () -> TenantScopes.visibleToCurrentCaller(8L))).isTrue();
        assertThat(TenantScopes.platformCaller()).isTrue();
    }
}
