package io.softa.starter.user.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.base.utils.NavIds;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.EntitlementService;
import io.softa.framework.orm.service.ModelService;

/**
 * A role in a customer tenant grants only what faces that tenant: no navigation of the platform's own
 * ({@code permission.platform-nav-prefixes}), none of a module the tenant's plan does not entitle, and
 * no permission under such a navigation.
 *
 * <p>The ceiling on what a role manager may hand out ({@link RoleGrantCeiling}) does not bind a tenant
 * admin — whatever it holds it may pass on — and the wizard saves whatever navigations the request
 * names. The role screen offers only the tenant's own pages, but the endpoint took any id, so a tenant
 * admin could give an ordinary user the platform's pages: endpoints that act on shared models, every
 * tenant's rows. Refused here for everyone saving inside a customer tenant, the admin included. The
 * platform tenant's roles are the platform's own, and a deployment without tenants has no other tenant
 * to protect.
 */
@Component
public class RoleTenantFacingGuard {

    private static final String M_PERMISSION = "Permission";

    private final ModelService<?> modelService;

    @Value("${permission.platform-nav-prefixes:}")
    private String platformNavPrefixesCsv;

    /** Optional: without a plan gate every module counts as entitled. */
    @Autowired(required = false)
    private EntitlementService entitlementService;

    public RoleTenantFacingGuard(ModelService<?> modelService) {
        this.modelService = modelService;
    }

    /**
     * Refuse the wizard's navigation rows ({@code [{navigationId, permissionIds}]}) when one of them, or one
     * of their permissions, does not face the current tenant.
     */
    public void assertTenantFacing(JsonNode roleNavigations) {
        Context context = ContextHolder.getContext();
        Long tenantId = context == null ? null : context.getTenantId();
        if (context == null || context.isSkipPermissionCheck() || tenantId == null
                || BaseConstant.PLATFORM_TENANT_ID.equals(tenantId)
                || roleNavigations == null || !roleNavigations.isArray()) {
            return;
        }
        Set<String> navigationIds = new LinkedHashSet<>();
        // permission id → the navigation of the row it was filed under
        Map<String, String> rowOf = new LinkedHashMap<>();
        for (JsonNode row : roleNavigations) {
            JsonNode nav = row.get("navigationId");
            String navId = nav == null || nav.isNull() ? null : nav.asString();
            if (navId != null) {
                navigationIds.add(navId);
            }
            List<String> pids = JsonUtils.toStringList(row.get("permissionIds"), true);
            if (pids != null) {
                pids.forEach(pid -> rowOf.putIfAbsent(pid, navId));
            }
        }
        Set<String> permissionIds = rowOf.keySet();
        Map<String, String> navigationOf = new HashMap<>();
        if (!permissionIds.isEmpty()) {
            List<Map<String, Object>> rows = modelService.searchList(M_PERMISSION,
                    new FlexQuery(List.of("id", "navigationId"), new Filters().in("id", permissionIds)));
            rows.forEach(r -> navigationOf.put(String.valueOf(r.get("id")), (String) r.get("navigationId")));
        }
        Set<String> entitled = entitlementService == null ? null : entitlementService.entitledModules(tenantId);
        List<String> refused = new ArrayList<>();
        for (String navId : navigationIds) {
            if (!facesTenant(navId, entitled)) {
                refused.add(navId);
            }
        }
        for (String permissionId : permissionIds) {
            // Judged by the navigation it is registered under. One the catalog no longer knows names no
            // endpoint — the endpoint index is built from the same catalog — so it is judged by its row's,
            // and a role still carrying a permission since removed can be saved.
            String registered = navigationOf.get(permissionId);
            if (!facesTenant(registered != null ? registered : rowOf.get(permissionId), entitled)) {
                refused.add(permissionId);
            }
        }
        if (!refused.isEmpty()) {
            throw new BusinessException("A role in this tenant cannot grant {0}: a platform page, or a module "
                    + "the tenant''s plan does not include.", String.join(", ", refused));
        }
    }

    private boolean facesTenant(String navId, Set<String> entitled) {
        if (navId == null || isPlatformNav(navId)) {
            return false;
        }
        if (entitled == null) {
            return true;
        }
        String moduleId = NavIds.moduleOf(navId);
        return moduleId == null || entitled.contains(moduleId);
    }

    private boolean isPlatformNav(String navId) {
        if (platformNavPrefixesCsv == null || platformNavPrefixesCsv.isBlank()) {
            return false;
        }
        for (String prefix : platformNavPrefixesCsv.split(",")) {
            String p = prefix.trim();
            if (!p.isEmpty() && navId.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
