package io.softa.starter.message.quota.service.impl;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.quota.entity.TenantMessageQuota;
import io.softa.starter.message.quota.service.TenantMessageQuotaService;
import io.softa.starter.message.quota.validation.TenantMessageQuotaWriteValidator;

/**
 * Implementation of {@link TenantMessageQuotaService}.
 * <p>
 * Write paths are structurally platform-only: {@link TenantMessageQuotaWriteValidator} runs at the
 * model service's write roots, so neither programmatic callers nor the admin UI — single rows or
 * batches — can configure a quota from a tenant session, regardless of role grants.
 */
@Service
public class TenantMessageQuotaServiceImpl extends EntityServiceImpl<TenantMessageQuota, Long>
        implements TenantMessageQuotaService {

    @Autowired
    private MessageProperties messageProperties;

    @Override
    public ResolvedLimits resolveLimits(long tenantId) {
        // Skip permission checks: this read serves the preparation of a send
        // record, in the record's context, and the registry is platform-owned
        // data no caller needs a grant for. The model is not multiTenant, so no tenant
        // filter applies either way.
        Context ctx = ContextHolder.cloneContext();
        ctx.setSkipPermissionCheck(true);
        Optional<TenantMessageQuota> row = ContextHolder.callWith(ctx,
                () -> searchOne(new Filters().eq(TenantMessageQuota::getTenantId, tenantId)));
        MessageProperties.Quota defaults = messageProperties.getQuota();
        return new ResolvedLimits(
                row.map(TenantMessageQuota::getMailMonthlyLimit).orElse(defaults.getMailMonthlyDefault()),
                row.map(TenantMessageQuota::getSmsMonthlyLimit).orElse(defaults.getSmsMonthlyDefault()),
                row.map(TenantMessageQuota::getMailDailyLimit).orElse(defaults.getMailDailyDefault()),
                row.map(TenantMessageQuota::getSmsDailyLimit).orElse(defaults.getSmsDailyDefault()));
    }

}
