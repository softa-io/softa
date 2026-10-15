package io.softa.starter.metadata.seed;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.metadata.enums.SeedSyncTriggerType;

/**
 * When a company is saved in a country, gives its tenant the tenant files of that country it never had —
 * a tenant's countries are its default country and those of its companies.
 *
 * <p>A write validator only for the hook it offers on every company write; it rejects nothing. The tenant
 * is looked at once the write commits, so a write that fails triggers nothing, and a tenant already due
 * that country's files is left as it is.
 */
@Slf4j
@Component
public class CompanyCountrySeedTrigger implements ModelWriteValidator {

    private static final String PENDING = CompanyCountrySeedTrigger.class.getName() + ".tenants";

    private final ObjectProvider<SeedSyncService> seedSyncService;

    public CompanyCountrySeedTrigger(ObjectProvider<SeedSyncService> seedSyncService) {
        this.seedSyncService = seedSyncService;
    }

    @Override
    public boolean supports(String modelName) {
        return ModelConstant.COMPANY_MODEL.equals(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        if (countryOf(ctx.row()) != null) {
            afterCommit();
        }
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        Object country = countryOf(ctx.row());
        if (country != null && !Objects.equals(country, ctx.originalRow() == null ? null : countryOf(ctx.originalRow()))) {
            afterCommit();
        }
    }

    private static Object countryOf(Map<String, Object> row) {
        return row == null ? null : row.get(ModelConstant.COUNTRY_FIELD);
    }

    /** Once per transaction, whatever number of companies it writes. */
    @SuppressWarnings("unchecked")
    private void afterCommit() {
        Long tenantId = ContextHolder.getContext().getTenantId();
        if (tenantId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            reconcileLater(Set.of(tenantId));
            return;
        }
        Set<Long> tenants = (Set<Long>) TransactionSynchronizationManager.getResource(PENDING);
        if (tenants == null) {
            Set<Long> pending = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(PENDING, pending);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING);
                    if (status == STATUS_COMMITTED) {
                        reconcileLater(pending);
                    }
                }
            });
            tenants = pending;
        }
        tenants.add(tenantId);
    }

    /** Off the request: the company is saved; bringing its tenant up to date follows through the MQ. */
    private void reconcileLater(Set<Long> tenants) {
        List<Long> ids = List.copyOf(tenants);
        Thread.ofVirtual().name("seed-country-added").start(() -> {
            try {
                seedSyncService.getObject().reconcile(ids, SeedSyncTriggerType.COUNTRY_ADDED);
            } catch (RuntimeException e) {
                log.error("Could not give tenants {} the seed files of their new company country", ids, e);
            }
        });
    }
}
