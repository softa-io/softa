package io.softa.starter.permission.scope;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.EmpInfo;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.filter.context.ContextEnricher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HR context loader — a framework {@link ContextEnricher} bean that runs per
 * HTTP request and populates {@link Context#empInfo} from cache or DB.
 *
 * <h3>Convention-driven, corehr-free (2026-07-15)</h3>
 * Reads the {@code Employee} / {@code Department} models <b>generically by model
 * name</b> via {@link ModelService} + conventional HR field names
 * ({@code userId} / {@code departmentId} / {@code legalEntityId} /
 * {@code picEmpId} / {@code hrbpEmpId} / …), so this enricher lives in the
 * framework without importing corehr entities. An app with no {@code Employee}
 * model degrades to a no-op (pure users, no {@link EmpInfo}).
 *
 * <p>Materializes one {@link EmpInfo} per cache miss, cached in Redis (1-month
 * TTL, key {@code empinfo:{userId}}); the HR app's {@code EmployeeChangedEvent}
 * adapter evicts it on transfers. Serves both framework macro substitution
 * ({@code {{USER_DEPT_ID}}}) and the HR scope contributors (SELF / DIRECT_REPORTS
 * / DEPT_SUBTREE / MANAGED_DEPARTMENTS) which read it via
 * {@code ContextHolder.getContext().getEmpInfo()}.
 *
 * <p>The DB reads below run with {@code skipPermissionCheck=true}, set manually in
 * {@link #buildFromDb} — see that method for why the {@code @SkipPermissionCheck}
 * annotation cannot do it here, and what broke while it appeared to.
 */
@Slf4j
@Component
@Order(ContextEnricher.ORDER_IDENTITY)
@RequiredArgsConstructor
public class EmployeeContextEnricher implements ContextEnricher {

    /** HR-domain conventions (kept here, not in softa core). */
    private static final String EMPLOYEE_MODEL = "Employee";
    private static final String DEPARTMENT_MODEL = "Department";

    private final ModelService<Long> modelService;
    private final CacheService cacheService;

    @Override
    public void enrich(Context context) {
        if (context.getUserId() == null) return;
        // Non-HR app (no Employee model) → skip the Redis read + DB load entirely.
        if (!ModelManager.existModel(EMPLOYEE_MODEL)) return;
        EmpInfo info = loadCached(context.getUserId());
        if (info != null) context.setEmpInfo(info);
    }

    private EmpInfo loadCached(Long userId) {
        String key = RedisConstant.EMP_INFO + userId;
        EmpInfo cached = cacheService.get(key, EmpInfo.class);
        if (cached != null) return cached;
        EmpInfo built = buildFromDb(userId);
        if (built == null) return null;
        cacheService.save(key, built, RedisConstant.ONE_MONTH);
        return built;
    }

    /**
     * Load the caller's employee row + managed departments.
     *
     * <p>Runs on a copy of the request context with {@code skipPermissionCheck=true} — a row-scope
     * waiver only; the tenant filter still applies, so this reads a
     * single tenant's data. The waiver is applied in code rather than via {@code @SkipPermissionCheck}:
     * that annotation is Spring-AOP advice and only fires on calls that arrive through the bean proxy.
     * This method is reached by self-invocation from {@link #loadCached}, and
     * {@link #collectManagedDeptIds} is {@code private} — neither is ever advised, so the annotation
     * was inert and these reads ran under full row-scope enforcement.
     *
     * <p>Why that mattered: row scope resolves SELF / DIRECT_REPORTS / DEPT_SUBTREE against
     * {@code Context.empInfo} — precisely what this method exists to build. So a non-admin
     * caller matched no Employee row here (fail-closed), EmpInfo stayed null for the whole
     * request, and every employee-anchored scope silently degraded to "no rows" while a CUSTOM
     * rule referencing {@code USER_EMP_ID} / {@code USER_DEPT_ID} failed outright at
     * SQL-build time ("Not support the env parameter").
     */
    EmpInfo buildFromDb(Long userId) {
        // Non-HR app (no Employee model) → no EmpInfo; caller treats as pure user.
        if (!ModelManager.existModel(EMPLOYEE_MODEL)) return null;
        // Unbound context (scheduler / MQ threads): permission checks are already bypassed, so the
        // reads run as they are.
        if (!ContextHolder.existContext()) return readIdentity(userId);
        // Read on a COPY rather than mutating the live context and restoring it. The Context is
        // shared by the whole request, so a restore missed on any path would leave every later query
        // unscoped — a far wider failure than the one this fixes. A copy cannot leak.
        Context isolated = ContextHolder.getContext().copy();
        // Row scope resolves SELF / DIRECT_REPORTS / DEPT_SUBTREE against Context.empInfo — precisely
        // what these reads exist to produce — so leaving it on deadlocks: a non-admin matches no
        // Employee row, EmpInfo stays null, and every employee-anchored scope silently yields nothing.
        isolated.setSkipPermissionCheck(true);
        // Identity is prior to any view of the data: these reads must not be narrowed by company or
        // country. No company narrowing exists at the ORM layer any more, and MultiCountryScope
        // returns early on a blank `companyCountry`, which CompanyCountryEnricher (ORDER_DERIVED) has
        // not written yet at ORDER_IDENTITY.
        return ContextHolder.callWith(isolated, () -> readIdentity(userId));
    }

    /** The two reads themselves. Always called with a context that already waives row scope and the
     *  company selection — see {@link #buildFromDb}. */
    private EmpInfo readIdentity(Long userId) {
        Map<String, Object> me = modelService.searchOne(
                EMPLOYEE_MODEL, new FlexQuery(Filters.of("userId", Operator.EQUAL, userId))).orElse(null);
        if (me == null || me.get("id") == null) {
            log.debug("EmployeeContextEnricher — user {} has no linked Employee row (pure user)", userId);
            return null;
        }
        EmpInfo info = new EmpInfo();
        info.setEmpId(coerceLong(me.get("id")));
        info.setName(asString(me.get("fullName")));
        info.setEmail(asString(me.get("workEmail")));
        info.setPhone(asString(me.get("workPhone")));
        info.setDeptId(coerceLong(me.get("departmentId")));
        info.setPositionId(coerceLong(me.get("jobPositionId")));
        // The org affiliation feeds USER_COMP_ID, so it reads the company AXIS field — after
        // the split, Employee.legalEntityId still exists but names the signing entity, an
        // attribute rather than the axis.
        info.setCompanyId(coerceLong(me.get(ModelConstant.COMPANY_FIELD)));
        // Same reason as the axis field above, and the same class of name: the framework hard-codes
        // "tenantId" elsewhere too, so a literal here is a second copy that can drift out of step.
        // The plain literals around it name HCM's own columns — nothing else spells those, so
        // there is no copy to keep aligned.
        info.setTenantId(coerceLong(me.get(ModelConstant.TENANT_ID)));
        info.setManagedDeptIds(collectManagedDeptIds(info.getEmpId()));
        return info;
    }

    /** Departments the employee directly heads — {@code picEmpId} (department
     *  head) OR {@code hrbpEmpId} (HR business partner). Subtree expansion happens
     *  later via the {@code CHILD_OF_ID} filter operator. Read enabled or not, as the department tree is:
     *  disabling a department does not take its people and records from the person heading it.
     *
     *  <p>No {@code @SkipPermissionCheck}: Spring AOP never advises a private method. The
     *  caller {@link #buildFromDb} has already set the flag for this read. */
    private Set<Long> collectManagedDeptIds(Long empId) {
        if (empId == null || !ModelManager.existModel(DEPARTMENT_MODEL)) return new HashSet<>();
        Filters f = Filters.of("picEmpId", Operator.EQUAL, empId)
                .or("hrbpEmpId", Operator.EQUAL, empId);
        FlexQuery query = new FlexQuery(f);
        query.setFilterControl(FilterControl.bypassActiveControl());
        List<Map<String, Object>> depts = modelService.searchList(DEPARTMENT_MODEL, query);
        Set<Long> out = new HashSet<>(depts.size());
        for (Map<String, Object> d : depts) {
            Long id = coerceLong(d.get("id"));
            if (id != null) out.add(id);
        }
        return out;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long coerceLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof CharSequence cs && !cs.isEmpty()) {
            try {
                return Long.parseLong(cs.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
