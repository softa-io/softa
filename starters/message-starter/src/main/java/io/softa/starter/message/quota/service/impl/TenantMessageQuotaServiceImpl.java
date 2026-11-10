package io.softa.starter.message.quota.service.impl;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.quota.entity.TenantMessageQuota;
import io.softa.starter.message.quota.service.TenantMessageQuotaService;
import io.softa.starter.message.shared.TenantScopes;

/**
 * Implementation of {@link TenantMessageQuotaService}.
 * <p>
 * Write paths are structurally platform-only: {@link #assertPlatformScope} is
 * applied in the service overrides AND in the controller's shadowed write
 * endpoints, so neither programmatic callers nor the admin UI can configure a
 * quota from a tenant session — regardless of role grants.
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
        Long mailLimit = row.map(TenantMessageQuota::getMailMonthlyLimit).orElse(null);
        Long smsLimit = row.map(TenantMessageQuota::getSmsMonthlyLimit).orElse(null);
        return new ResolvedLimits(
                mailLimit != null ? mailLimit : defaults.getMailMonthlyDefault(),
                smsLimit != null ? smsLimit : defaults.getSmsMonthlyDefault());
    }

    @Override
    public void assertPlatformScope() {
        if (TenantScopes.multiTenancyEnabled()
                && TenantScopes.currentTenantOrPlatform() != TenantScopes.PLATFORM) {
            throw new BusinessException(
                    "Message quotas are configured by platform operations only — "
                    + "they cannot be modified from a tenant scope.");
        }
    }

    @Override
    public Long createOne(TenantMessageQuota entity) {
        assertPlatformScope();
        return super.createOne(entity);
    }

    @Override
    public boolean updateOne(TenantMessageQuota entity) {
        assertPlatformScope();
        return super.updateOne(entity);
    }

    @Override
    public boolean deleteById(Long id) {
        assertPlatformScope();
        return super.deleteById(id);
    }
}
