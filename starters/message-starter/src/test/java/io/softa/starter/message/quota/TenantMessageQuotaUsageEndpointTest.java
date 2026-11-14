package io.softa.starter.message.quota;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.starter.message.quota.controller.TenantMessageQuotaController;
import io.softa.starter.message.quota.dto.MessageQuotaUsageDTO;
import io.softa.starter.message.quota.service.TenantMessageQuotaService;
import io.softa.starter.message.shared.SendQuotaGuard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /TenantMessageQuota/usage}: a tenant reads its own bucket — this month and today — and no
 * other; the platform reads any.
 */
class TenantMessageQuotaUsageEndpointTest {

    private final TenantMessageQuotaController controller = new TenantMessageQuotaController();
    private final TenantMessageQuotaService service = mock(TenantMessageQuotaService.class);
    private final SendQuotaGuard quotaGuard = mock(SendQuotaGuard.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "service", service);
        ReflectionTestUtils.setField(controller, "quotaGuard", quotaGuard);
        when(service.resolveLimits(anyLong()))
                .thenReturn(new TenantMessageQuotaService.ResolvedLimits(10_000L, 10_000L, 2_000L, 2_000L));
        when(quotaGuard.usedToday("mail", 5L)).thenReturn(12L);
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
    }

    @AfterEach
    void tearDown() {
        SystemConfig.env = null;
    }

    private static <T> T as(long tenantId, java.util.function.Supplier<T> action) {
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        return ContextHolder.callWith(ctx, action::get);
    }

    @Test
    void aTenantReadsItsOwnBucket_withTodaysCount() {
        MessageQuotaUsageDTO usage = as(5L, () -> controller.usage(5L, null).getData());

        assertEquals(12L, usage.getMailUsedToday());
        assertEquals(2_000L, usage.getMailDailyLimit());
    }

    @Test
    void aTenantCannotReadAnotherTenantsBucket() {
        assertThrows(PermissionException.class, () -> as(5L, () -> controller.usage(7L, null)));
    }

    @Test
    void thePlatformReadsAnyBucket() {
        MessageQuotaUsageDTO usage = as(-1L, () -> controller.usage(5L, null).getData());

        assertEquals(5L, usage.getTenantId());
    }
}
