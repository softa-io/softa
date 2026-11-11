package io.softa.starter.message.quota.controller;

import java.time.YearMonth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.quota.dto.MessageQuotaUsageDTO;
import io.softa.starter.message.quota.entity.TenantMessageQuota;
import io.softa.starter.message.quota.service.TenantMessageQuotaService;
import io.softa.starter.message.shared.SendQuotaGuard;
import io.softa.starter.message.shared.TenantScopes;

/**
 * REST controller for tenant message quotas — platform operations only.
 * <p>
 * The writes are the generic {@code /TenantMessageQuota/...} endpoints: every one of them, batches
 * included, passes {@link io.softa.starter.message.quota.validation.TenantMessageQuotaWriteValidator}
 * at the model service's write roots, so no tenant session — role grants regardless — may configure its
 * own ceiling.
 */
@Tag(name = "TenantMessageQuota")
@RestController
@RequestMapping("/TenantMessageQuota")
public class TenantMessageQuotaController
        extends EntityController<TenantMessageQuotaService, TenantMessageQuota, Long> {

    @Autowired
    private SendQuotaGuard quotaGuard;

    @Operation(summary = "One quota bucket's usage vs resolved limits: the month asked for, and today",
            description = "tenantId = -1 reads the platform's own bucket. month defaults to the "
                    + "current calendar month (format yyyy-MM); the daily figures are always today's. "
                    + "A tenant session may only read its own bucket; any bucket is readable from the "
                    + "platform scope.")
    @GetMapping("/usage")
    public ApiResponse<MessageQuotaUsageDTO> usage(
            @RequestParam Long tenantId,
            @RequestParam(required = false) String month) {
        // Usage is per-tenant consumption data: a tenant may inspect its own
        // bucket, everything else is platform-operations territory.
        if (TenantScopes.currentTenantOrPlatform() != tenantId) {
            TenantScopes.requirePlatformCaller("Another tenant's message usage");
        }
        YearMonth target = month != null ? YearMonth.parse(month) : YearMonth.now();
        TenantMessageQuotaService.ResolvedLimits limits = service.resolveLimits(tenantId);
        MessageQuotaUsageDTO dto = new MessageQuotaUsageDTO();
        dto.setTenantId(tenantId);
        dto.setMonth(target.toString());
        dto.setMailUsed(quotaGuard.usage("mail", tenantId, target));
        dto.setMailMonthlyLimit(limits.mailMonthlyLimit());
        dto.setSmsUsed(quotaGuard.usage("sms", tenantId, target));
        dto.setSmsMonthlyLimit(limits.smsMonthlyLimit());
        dto.setMailUsedToday(quotaGuard.usedToday("mail", tenantId));
        dto.setMailDailyLimit(limits.mailDailyLimit());
        dto.setSmsUsedToday(quotaGuard.usedToday("sms", tenantId));
        dto.setSmsDailyLimit(limits.smsDailyLimit());
        return ApiResponse.success(dto);
    }
}
