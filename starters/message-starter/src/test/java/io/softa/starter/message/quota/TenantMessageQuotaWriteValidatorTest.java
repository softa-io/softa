package io.softa.starter.message.quota;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.enums.AccessType;
import io.softa.starter.message.quota.validation.TenantMessageQuotaWriteValidator;

/**
 * Every write of a message quota — single rows and batches, create, update and delete — is the
 * platform's: the generic batch endpoints used to let a tenant session through.
 */
class TenantMessageQuotaWriteValidatorTest {

    private final TenantMessageQuotaWriteValidator validator = new TenantMessageQuotaWriteValidator();

    @AfterEach
    void tearDown() {
        SystemConfig.env = null;
    }

    private static void as(long tenantId, Runnable action) {
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        ContextHolder.runWith(ctx, action);
    }

    @Test
    void appliesToTheQuotaModelOnly() {
        Assertions.assertTrue(validator.supports("TenantMessageQuota"));
        Assertions.assertFalse(validator.supports("TenantMessageUsage"));
    }

    @Test
    void aTenantCannotCreateUpdateOrDeleteQuotas() {
        List<Map<String, Object>> rows = List.of(Map.of("tenantId", 5L, "mailMonthlyLimit", 1_000_000L));
        as(5L, () -> {
            Assertions.assertThrows(PermissionException.class,
                    () -> validator.validateBatch("TenantMessageQuota", rows, AccessType.CREATE, new java.util.HashMap<>()));
            Assertions.assertThrows(PermissionException.class,
                    () -> validator.validateBatch("TenantMessageQuota", rows, AccessType.UPDATE, new java.util.HashMap<>()));
            Assertions.assertThrows(PermissionException.class,
                    () -> validator.validateDelete("TenantMessageQuota", List.of(1L, 2L)));
        });
    }

    @Test
    void thePlatformMay() {
        List<Map<String, Object>> rows = List.of(Map.of("tenantId", 5L));
        as(-1L, () -> {
            Assertions.assertDoesNotThrow(
                    () -> validator.validateBatch("TenantMessageQuota", rows, AccessType.CREATE, new java.util.HashMap<>()));
            Assertions.assertDoesNotThrow(() -> validator.validateDelete("TenantMessageQuota", List.of(1L)));
        });
    }

    @Test
    void aSingleTenantDeploymentIsUnrestricted() {
        // No SystemConfig.env: multi-tenancy off, so there is no platform to reserve the quotas for.
        Assertions.assertDoesNotThrow(() -> validator.validateDelete("TenantMessageQuota", List.of(1L)));
    }
}
