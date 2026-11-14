package io.softa.framework.orm.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Timezone;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;

/**
 * "Today" as a company sees it.
 *
 * <p>Whether a dated record has started, which assignment is in effect, which change is due — each is a question
 * about the company's own calendar. A server running in another zone answers it with a different date for hours
 * every day, so the date is read in the company's time zone ({@code BaseCompany.timeZone}).
 *
 * <p>The order: the company's own time zone, else the tenant's default, else the server's. The last tier keeps a
 * misconfigured company working rather than failing the write.
 *
 * <p>The company is read past the caller's scope: a time zone is configuration, and a caller who may not see the
 * company must not be handed another calendar because of it. An application with no {@code Company} model, or one
 * without its {@code timeZone}, gets the tenant's calendar.
 */
@Component
public class CompanyClock {

    static final String TIME_ZONE = "timeZone";

    private final ModelService<Long> modelService;
    private final ObjectProvider<TenantInfoService> tenantInfoService;

    public CompanyClock(@Lazy ModelService<Long> modelService, ObjectProvider<TenantInfoService> tenantInfoService) {
        this.modelService = modelService;
        this.tenantInfoService = tenantInfoService;
    }

    /** Today in the company's zone; in the tenant's, or the server's, when it has none. */
    public LocalDate today(Long companyId) {
        return LocalDate.now(zoneOf(companyId));
    }

    /** The zone {@link #today} reads. */
    public ZoneId zoneOf(Long companyId) {
        return unscoped(() -> resolveZone(timeZoneOf(companyId), tenantDefault()));
    }

    /**
     * Every company of the tenant, disabled ones included, by the day it is there now — for a list that compares
     * dates across companies in one query. Usually one day, two for the hours the zones straddle.
     */
    public Map<LocalDate, Set<Long>> companiesByToday() {
        return unscoped(() -> {
            Map<LocalDate, Set<Long>> byToday = new HashMap<>();
            if (!hasTimeZone()) {
                return byToday;
            }
            Timezone tenantDefault = tenantDefault();
            FlexQuery query = new FlexQuery(List.of(ModelConstant.ID, TIME_ZONE));
            query.setFilterControl(FilterControl.bypassActiveControl());
            for (Map<String, Object> company : modelService.searchList(ModelConstant.COMPANY_MODEL, query)) {
                Long id = idOf(company.get(ModelConstant.ID));
                if (id == null) {
                    continue;
                }
                LocalDate today = LocalDate.now(resolveZone(zoneIdOf(company.get(TIME_ZONE)), tenantDefault));
                byToday.computeIfAbsent(today, day -> new HashSet<>()).add(id);
            }
            return byToday;
        });
    }

    /** The three tiers on their own, for a caller that has the two settings in hand already. */
    public static ZoneId resolveZone(String companyTimeZone, Timezone tenantDefault) {
        ZoneId company = parse(companyTimeZone);
        if (company != null) {
            return company;
        }
        if (tenantDefault != null) {
            try {
                return tenantDefault.toZoneId();
            } catch (DateTimeException ignored) {
                // An unreadable tenant setting falls through to the server's zone.
            }
        }
        return ZoneId.systemDefault();
    }

    private String timeZoneOf(Long companyId) {
        if (companyId == null || !hasTimeZone()) {
            return null;
        }
        return modelService.getById(ModelConstant.COMPANY_MODEL, companyId, List.of(TIME_ZONE))
                .map(company -> zoneIdOf(company.get(TIME_ZONE)))
                .orElse(null);
    }

    private Timezone tenantDefault() {
        Long tenantId = ContextHolder.getContext().getTenantId();
        TenantInfoService tenants = tenantInfoService.getIfAvailable();
        return tenantId == null || tenants == null ? null : tenants.getDefaultTimezone(tenantId);
    }

    private static boolean hasTimeZone() {
        return ModelManager.existModel(ModelConstant.COMPANY_MODEL)
                && ModelManager.getModelFieldOrNull(ModelConstant.COMPANY_MODEL, TIME_ZONE) != null;
    }

    /** A zone reference as its id: the plain id, or the {@code {id, …}} object a read may hand back. */
    private static String zoneIdOf(Object value) {
        Object id = value instanceof Map<?, ?> reference ? reference.get(ModelConstant.ID) : value;
        return id == null ? null : String.valueOf(id);
    }

    private static Long idOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ZoneId parse(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(zoneId);
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private static <T> T unscoped(Supplier<T> read) {
        Context unscoped = ContextHolder.cloneContext();
        unscoped.setSkipPermissionCheck(true);
        return ContextHolder.callWith(unscoped, read::get);
    }
}
