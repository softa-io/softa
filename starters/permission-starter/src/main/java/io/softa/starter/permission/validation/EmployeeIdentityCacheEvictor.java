package io.softa.starter.permission.validation;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.permission.scope.EmployeeContextEnricher;
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
 * <p>Deleting an employee drops the cache of its user too: the person no longer sits anywhere, and an identity
 * cached from the deleted row would keep placing them in its department for a month. A placing field the model
 * stores as a cascade of another one ({@code companyId} cascaded from the legal entity) changes when that other
 * field does, though the patch names only the source, so a change to the source counts as a change of placement.
 *
 * <p>The departments a person manages are in the same cache, read from the department's managing fields
 * ({@link EmployeeContextEnricher#MANAGING_FIELDS}). A department created, deleted or given another manager drops
 * the cache of the people it names, those it named before as well as those it names now: one gains the
 * department, the other has to lose it.
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
    private final ModelService<?> modelService;

    public EmployeeIdentityCacheEvictor(CacheService cacheService, @Lazy ModelService<?> modelService) {
        this.cacheService = cacheService;
        this.modelService = modelService;
    }

    @Override
    public boolean supports(String modelName) {
        return ModelConstant.EMPLOYEE_MODEL.equals(modelName) || ModelConstant.DEPARTMENT_MODEL.equals(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        if (ModelConstant.DEPARTMENT_MODEL.equals(ctx.modelName())) {
            evictManagers(managersOf(ctx.row()), ctx.row());
        }
    }

    @Override
    public void validateDelete(String modelName, List<? extends Serializable> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (ModelConstant.EMPLOYEE_MODEL.equals(modelName)) {
            evictUsersOfEmployees(ids);
            return;
        }
        if (!ModelConstant.DEPARTMENT_MODEL.equals(modelName)) {
            return;
        }
        List<String> fields = new ArrayList<>(EmployeeContextEnricher.MANAGING_FIELDS);
        fields.add(ModelConstant.ID);
        Set<Long> managers = new HashSet<>();
        try {
            FlexQuery query = new FlexQuery(fields, new Filters().in(ModelConstant.ID, ids));
            query.setFilterControl(FilterControl.bypassActiveControl());
            ContextUtils.withoutPermissionChecks(() -> modelService.searchList(modelName, query))
                    .forEach(row -> managers.addAll(managersOf(row)));
        } catch (RuntimeException e) {
            log.warn("EmployeeIdentityCacheEvictor — reading the managers of departments {} failed; TTL will expire "
                    + "their caches", ids, e);
        }
        evictManagers(managers, Map.of());
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        if (ModelConstant.DEPARTMENT_MODEL.equals(ctx.modelName())) {
            Map<String, Object> stored = ctx.originalRow();
            if (stored != null) {
                evictManagers(managersChanging(ctx.patch(), stored), stored);
            }
            return;
        }
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
        Long tenantId = tenantOf(stored);
        afterCommit(() -> users.forEach(user -> evict(tenantId, user)));
    }

    /** Drops the caches of the users of employees about to be deleted, read now while the rows still exist. */
    private void evictUsersOfEmployees(List<? extends Serializable> ids) {
        Set<Long> users = new HashSet<>();
        Long tenantId = null;
        try {
            FlexQuery query = new FlexQuery(List.of(ModelConstant.ID, USER_FIELD, ModelConstant.TENANT_ID),
                    new Filters().in(ModelConstant.ID, ids));
            query.setFilterControl(FilterControl.bypassActiveControl());
            for (Map<String, Object> row : ContextUtils.withoutPermissionChecks(
                    () -> modelService.searchList(ModelConstant.EMPLOYEE_MODEL, query))) {
                addIfPresent(users, CompanyReferenceGrantValidator.idOf(row.get(USER_FIELD)));
                if (tenantId == null) {
                    tenantId = CompanyReferenceGrantValidator.idOf(row.get(ModelConstant.TENANT_ID));
                }
            }
        } catch (RuntimeException e) {
            log.warn("EmployeeIdentityCacheEvictor — reading the users of employees {} failed; TTL will expire "
                    + "their caches", ids, e);
        }
        if (users.isEmpty()) {
            return;
        }
        Long tenant = tenantId != null ? tenantId : tenantOf(Map.of());
        afterCommit(() -> users.forEach(user -> evict(tenant, user)));
    }

    /** The tenant of a stored row, else the request's: a row read without its tenant column still has one. */
    private static Long tenantOf(Map<String, Object> row) {
        Long rowTenant = CompanyReferenceGrantValidator.idOf(row.get(ModelConstant.TENANT_ID));
        return rowTenant != null || !ContextHolder.existContext() ? rowTenant : ContextHolder.getContext().getTenantId();
    }

    private static Set<Long> managersOf(Map<String, Object> row) {
        Set<Long> managers = new HashSet<>();
        EmployeeContextEnricher.MANAGING_FIELDS.forEach(
                field -> addIfPresent(managers, CompanyReferenceGrantValidator.idOf(row.get(field))));
        return managers;
    }

    /** The people a write takes a department from or gives it to, in the fields it actually changes. */
    private static Set<Long> managersChanging(Map<String, Object> patch, Map<String, Object> stored) {
        Set<Long> managers = new HashSet<>();
        for (String field : EmployeeContextEnricher.MANAGING_FIELDS) {
            if (!patch.containsKey(field)) {
                continue;
            }
            Long next = CompanyReferenceGrantValidator.idOf(patch.get(field));
            Long previous = CompanyReferenceGrantValidator.idOf(stored.get(field));
            if (!Objects.equals(next, previous)) {
                addIfPresent(managers, next);
                addIfPresent(managers, previous);
            }
        }
        return managers;
    }

    /**
     * Drops the caches of these employees' users after commit. Their users are read now, past the caller's row
     * scope: the caller may not see who they are appointing, and an employee with no user has no cache.
     */
    private void evictManagers(Set<Long> employees, Map<String, Object> departmentRow) {
        if (employees.isEmpty()) {
            return;
        }
        Set<Long> users = new HashSet<>();
        try {
            FlexQuery query = new FlexQuery(List.of(ModelConstant.ID, USER_FIELD),
                    new Filters().in(ModelConstant.ID, employees));
            query.setFilterControl(FilterControl.bypassActiveControl());
            ContextUtils.withoutPermissionChecks(() -> modelService.searchList(ModelConstant.EMPLOYEE_MODEL, query))
                    .forEach(row -> addIfPresent(users, CompanyReferenceGrantValidator.idOf(row.get(USER_FIELD))));
        } catch (RuntimeException e) {
            log.warn("EmployeeIdentityCacheEvictor — reading the users of employees {} failed; TTL will expire "
                    + "their caches", employees, e);
        }
        if (users.isEmpty()) {
            return;
        }
        Long tenantId = tenantOf(departmentRow);
        afterCommit(() -> users.forEach(user -> evict(tenantId, user)));
    }

    private static boolean changesPlacement(Map<String, Object> patch, Map<String, Object> stored) {
        return PLACING_FIELDS.stream().anyMatch(field -> changes(patch, stored, field)
                || changes(patch, stored, cascadeSourceOf(field)));
    }

    private static boolean changes(Map<String, Object> patch, Map<String, Object> stored, String field) {
        return field != null && patch.containsKey(field)
                && !Objects.equals(CompanyReferenceGrantValidator.idOf(patch.get(field)),
                        CompanyReferenceGrantValidator.idOf(stored.get(field)));
    }

    /** The field a stored cascade of this placing field is computed from, or null when it is not one. */
    private static String cascadeSourceOf(String field) {
        MetaField meta = ModelManager.existModel(ModelConstant.EMPLOYEE_MODEL)
                ? ModelManager.getModelFieldOrNull(ModelConstant.EMPLOYEE_MODEL, field) : null;
        if (meta == null || meta.isDynamic() || meta.getCascadedField() == null || meta.getCascadedField().isBlank()) {
            return null;
        }
        String source = meta.getCascadedField().split("\\.", 2)[0];
        return source.equals(field) ? null : source;
    }

    private static void addIfPresent(Set<Long> users, Long user) {
        if (user != null) {
            users.add(user);
        }
    }

    /**
     * Runs the eviction after commit. The validator chain runs only inside the transactional write roots of
     * {@code ModelService}, so a synchronization is active; without one there is no commit to wait for, and the
     * eviction runs at once — before the write, which a concurrent rebuild could then read past.
     */
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
