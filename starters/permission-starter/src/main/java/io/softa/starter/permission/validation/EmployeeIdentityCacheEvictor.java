package io.softa.starter.permission.validation;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;

/**
 * Drops the caller identity cached for an employee when a write changes what it was built from.
 *
 * <p>{@code EmployeeContextEnricher} caches a user's employee for a month, and what the department grant's
 * own-department condition reaches, like every {@code USER_DEPT_ID} scope, comes from that cache. An application
 * can evict it from a domain event on its own service path, but a write through {@code ModelService} — the generic
 * model routes, an import — never passes there, and left the person reading their old department until the entry
 * expired. Here is the one place every write to the model goes through.
 *
 * <p>Refuses nothing: a write validator only because it sees the patch and the stored row together. Runs after
 * commit, so a rolled-back write leaves the cache as it was and a rebuild cannot read the row before the write.
 */
@Slf4j
@Component
@Order(200)
public class EmployeeIdentityCacheEvictor implements ModelWriteValidator {

    private static final String USER_FIELD = "userId";
    /** The fields of the employee the cached identity places them by. */
    private static final Set<String> PLACING_FIELDS =
            Set.of(ModelConstant.DEPARTMENT_FIELD, ModelConstant.COMPANY_FIELD, "jobPositionId", USER_FIELD);

    private final CacheService cacheService;

    public EmployeeIdentityCacheEvictor(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public boolean supports(String modelName) {
        return ModelConstant.EMPLOYEE_MODEL.equals(modelName);
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        Map<String, Object> stored = ctx.originalRow();
        if (stored == null || !changesPlacement(ctx.patch(), stored)) {
            return;
        }
        Set<Long> users = new HashSet<>();
        addIfPresent(users, CompanyReferenceGrantValidator.idOf(stored.get(USER_FIELD)));
        addIfPresent(users, CompanyReferenceGrantValidator.idOf(ctx.row().get(USER_FIELD)));
        if (users.isEmpty()) {
            return;
        }
        Long tenantId = CompanyReferenceGrantValidator.idOf(stored.get(ModelConstant.TENANT_ID));
        afterCommit(() -> users.forEach(user -> evict(tenantId, user)));
    }

    private static boolean changesPlacement(Map<String, Object> patch, Map<String, Object> stored) {
        return PLACING_FIELDS.stream().anyMatch(field -> patch.containsKey(field)
                && !Objects.equals(CompanyReferenceGrantValidator.idOf(patch.get(field)),
                        CompanyReferenceGrantValidator.idOf(stored.get(field))));
    }

    private static void addIfPresent(Set<Long> users, Long user) {
        if (user != null) {
            users.add(user);
        }
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void evict(Long tenantId, Long user) {
        try {
            cacheService.clear(RedisConstant.EMP_INFO + user);
            if (tenantId != null) {
                cacheService.clear(PermissionSnapshotProvider.userSnapshotKey(tenantId, user));
            }
        } catch (Throwable t) {
            log.warn("EmployeeIdentityCacheEvictor — evicting the caches of user {} failed; TTL will expire them", user, t);
        }
    }
}
