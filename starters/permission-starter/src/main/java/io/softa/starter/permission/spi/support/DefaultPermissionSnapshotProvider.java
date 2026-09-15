package io.softa.starter.permission.spi.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.base.enums.BuiltinRole;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.base.utils.NavIds;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.sensitive.SensitiveFieldSetCache;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;

/**
 * Default {@link PermissionSnapshotProvider} — builds a user's snapshot from the
 * standard RBAC config models, reading them <b>by name</b> ({@link ModelService}
 * 约定读) into private view DTOs so this engine never imports {@code user-starter}.
 * Relocated here from {@code user-starter}'s {@code PermissionInfoEnricher}
 * (2026-07-16, F1): the enricher was authoring-adjacent but is actually driven by
 * the enforce path (lazily built on the first authenticated request via the
 * interceptor's {@code snapshotProvider.get()}), so it belongs beside the engine.
 *
 * <h3>{@code @SkipPermissionCheck} on {@link #get}</h3>
 * The RBAC reads below must NOT be scope-filtered — otherwise the current user's
 * (empty) scope on {@code Role}/{@code RoleNavigation} would fail-closed to zero
 * rows, and {@code ScopeFilterAspect} → {@code PermissionServiceImpl} →
 * {@code snapshotProvider.get()} would recurse. The interceptor calls {@code get}
 * through the bean proxy, so the {@code @SkipPermissionCheck} {@code @Around}
 * fires and sets the context skip flag for the whole build (all nested
 * ModelService reads honor it).
 *
 * <h3>Standalone deployments</h3>
 * When the RBAC models are absent (pure-enforce microservice without
 * {@code user-starter}), {@link #loadFromDb} returns {@code null} → callers
 * fail-closed. Such a deployment supplies its own {@link PermissionSnapshotProvider}
 * (e.g. an {@link AbstractCacheAsideSnapshotProvider} subclass re-sourcing via RPC).
 *
 * <h3>Cache</h3>
 * Three tiers, same as the former enricher: request-scoped (a single request
 * calls {@code get} 4+ times — interceptor + row-scope/field-mask/write-guard
 * AOP), then Redis ({@code perm:{tenant}:user:{user}}, TTL 1h), then DB build.
 */
@Slf4j
public class DefaultPermissionSnapshotProvider implements PermissionSnapshotProvider {

    private static final int CACHE_TTL_SECONDS = RedisConstant.ONE_HOUR;
    private static final int ANCESTOR_DEPTH_CAP = 32;



    private static final String M_USER_ROLE_REL = "UserRoleRel";
    private static final String M_ROLE = "Role";
    private static final String M_ROLE_NAV = "RoleNavigation";
    private static final String M_ROLE_SCOPE = "RoleDataScope";
    private static final String M_ROLE_SFS = "RoleSensitiveFieldSet";
    private static final String M_NAV = "Navigation";
    private static final String M_PERMISSION = "Permission";
    private static final String M_USER_ACCOUNT = "UserAccount";
    private static final String F_CONSULTANT = "consultant";

    private final CacheService cacheService;
    private final ModelService<?> modelService;
    private final SensitiveFieldSetCache sensitiveFieldSetCache;
    /**
     * Compiles the company model's own scope rules into the filter this materialises the axis from.
     *
     * <p>A {@link Supplier} rather than the bean, for two reasons that happen to want the same thing.
     * The compiler reaches {@code ModelService} through {@code ScopeApplicabilityResolver}, which is the
     * wiring cycle the autoconfiguration breaks with {@code @Lazy} on its other collaborators — and
     * {@code @Lazy} cannot be used here, because it works by proxying and {@code ScopeRuleCompiler} is
     * final. Asking for it at first use needs no proxy. It also lets a pure-enforce deployment that
     * registers no compiler start rather than fail at wiring: the supplier yields null and the company
     * axis degrades to unrestricted, the same shape as the other absent-model degradations here.
     */
    private final Supplier<ScopeRuleCompiler> scopeRuleCompiler;
    /** Nav-id prefixes that are platform-only (never in a tenant admin's grant), e.g.
     *  {@code navigation.system.} / {@code navigation.studio.}. From {@code permission.platform-nav-prefixes}. */
    private final List<String> platformNavPrefixes;
    /**
     * Nav-id prefixes a tenant AND the platform both work in, e.g. {@code navigation.message.}. From
     * {@code permission.shared-nav-prefixes}.
     *
     * <p>A second list rather than a longer first one, and the two are <b>disjoint</b>. They answer
     * different questions: {@link #platformNavPrefixes} is what a tenant admin is kept OUT of, this is
     * what both audiences are let into. Folding them together would name system and studio twice —
     * once under each meaning — which is the shape that drifts.
     *
     * <p>Messaging is the case that needs it. Every model under it is multiTenant, so the platform is
     * not looking at a different module: it is looking at the same menus on its own tier, where the
     * verification-code and password-reset mails live. Take it away and nobody can edit the mail a
     * consultant logs in with.
     */
    private final List<String> sharedNavPrefixes;

    /** Plan (entitlement) gate — optional: a pure-enforce deployment without tenant-starter has none,
     *  in which case every module is treated as entitled (no plan narrowing). The SPI lives in
     *  softa-orm, so consuming it keeps this starter ⊥ of user-starter and tenant-starter alike. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.softa.framework.orm.service.EntitlementService entitlementService;

    /** leafNavId → root→leaf ancestor chain; lazily built from the Navigation
     *  tree (seed data — changes only on redeploy). */
    private volatile Map<String, List<String>> ancestorChains;

    public DefaultPermissionSnapshotProvider(CacheService cacheService,
                                             ModelService<?> modelService,
                                             SensitiveFieldSetCache sensitiveFieldSetCache,
                                             Supplier<ScopeRuleCompiler> scopeRuleCompiler,
                                             List<String> platformNavPrefixes,
                                             List<String> sharedNavPrefixes) {
        this.cacheService = cacheService;
        this.modelService = modelService;
        this.sensitiveFieldSetCache = sensitiveFieldSetCache;
        this.scopeRuleCompiler = scopeRuleCompiler;
        this.platformNavPrefixes = platformNavPrefixes == null ? List.of() : platformNavPrefixes;
        this.sharedNavPrefixes = sharedNavPrefixes == null ? List.of() : sharedNavPrefixes;
    }

    @Override
    @SkipPermissionCheck
    public PermissionInfo get(Long tenantId, Long userId) {
        String key = PermissionSnapshotProvider.userSnapshotKey(tenantId, userId);

        // Tier 1: request-scoped — same request, same user → memory.
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra != null) {
            Object stashed = ra.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
            if (stashed instanceof PermissionInfo pi) {
                return pi;
            }
        }

        // Tier 2: Redis.
        try {
            PermissionInfo cached = cacheService.get(key, PermissionInfo.class);
            if (cached != null) {
                stashInRequest(ra, key, cached);
                return cached;
            }
        } catch (Throwable t) {
            log.warn("PermissionInfo cache read failed for key={}; falling through to DB", key, t);
        }

        // Tier 3: build from RBAC config.
        PermissionInfo fresh = loadFromDb(tenantId, userId);
        if (fresh != null) {
            try {
                cacheService.save(key, fresh, CACHE_TTL_SECONDS);
            } catch (Throwable t) {
                log.warn("PermissionInfo cache write failed for key={}; continuing", key, t);
            }
            stashInRequest(ra, key, fresh);
        }
        return fresh;
    }

    private static void stashInRequest(RequestAttributes ra, String key, PermissionInfo pi) {
        if (ra != null) {
            ra.setAttribute(key, pi, RequestAttributes.SCOPE_REQUEST);
        }
    }

    // ─────────────────────── DB build (约定读) ───────────────────────

    /**
     * The companies these roles may reach — resolved from the role's data scope <b>on the company
     * model itself</b>, not from a grant table of its own.
     *
     * <h3>Why this and not a second store</h3>
     * The company axis and a data scope on {@code LegalEntity} bound the same thing, so configuring
     * them separately means configuring one thing twice: an administrator who narrowed the company
     * scope and left the grant alone would still see every company's departments and reports. Reading
     * the scope makes the row that bounds the company list also bound every model that belongs to a
     * company — one configuration, both effects. It also drops a bespoke table that duplicated what
     * the scope registry already does generically (ALL / CUSTOM, applicability, fail-closed).
     *
     * <p>That table ({@code RoleCompany}) was rejected on the grounds that a scope row is keyed per
     * model, so the company list would be stored once per multi-company model — eighteen copies kept in
     * lockstep. It is not: the list lives on the <b>company model's own</b> row, which is where "the
     * companies this role may reach" belongs literally rather than as a per-model duplicate, and the
     * eighteen are bounded from that one row. What the table did buy and this gives up is the reverse
     * question — "which roles can reach this company" — which is a plain query against a row per grant
     * and a JSON search against this. Nothing asks it today; when something does, it is a
     * {@code JSON_CONTAINS} over one row per role, not a schema change.
     *
     * <h3>The three states</h3>
     * <ul>
     *   <li><b>no rule at all</b> → {@code null}, unrestricted. Absence of configuration may never
     *       produce an empty set, or shipping the axis would blank every unconfigured role's screens.</li>
     *   <li><b>an {@code ALL} rule</b> → {@code null} as well, and deliberately not the materialised
     *       list of every id: a company created after this snapshot was cached would be missing from
     *       it for the rest of the hour, and "unrestricted" must not decay into "these ones".</li>
     *   <li><b>anything else</b> → the ids it resolves to, which may be <b>empty</b> — a role
     *       configured to reach no company, which is what a self-service employee is. Fail-closed is
     *       correct here precisely because it was configured.</li>
     * </ul>
     *
     * <h3>Staleness</h3>
     * A rule listing ids explicitly (what the wizard writes) resolves to exactly those and cannot go
     * stale. A predicate — {@code country = 'SG'} in a CUSTOM rule — is materialised here, so a legal
     * entity created afterwards is not included until the snapshot expires (1h) or a role write evicts
     * it. That is the cost of answering with ids rather than a filter, and it is bounded; the
     * alternative, carrying the filter into every multi-company read, would mean a correlated subquery
     * on every list in the application.
     *
     * <p>Absent model = an application without a company dimension (the framework's own demo apps),
     * which must cost neither a query nor an exception — same degradation as
     * {@code CompanyCountryEnricher}.
     *
     * @param modelScopeMap the scope rules already read for this user, keyed by model
     */
    Set<Long> readGrantedCompanyIds(Map<String, List<ScopeRule>> modelScopeMap) {
        if (!ModelManager.existModel(ModelConstant.COMPANY_MODEL)) {
            return null;
        }
        List<ScopeRule> rules = modelScopeMap.get(ModelConstant.COMPANY_MODEL);
        if (rules == null || rules.isEmpty()) {
            // Not configured. Checked before compiling on purpose: the compiler answers an empty rule
            // list with match-none, which is the right answer for "every rule degraded" and the wrong
            // one for "nobody configured this".
            return null;
        }
        ScopeRuleCompiler compiler = scopeRuleCompiler == null ? null : scopeRuleCompiler.get();
        if (compiler == null) {
            // A deployment that enforces without the scope engine — nothing can resolve the rules, so
            // the axis stays off rather than fail-closing every multi-company read.
            log.debug("No ScopeRuleCompiler available; the company axis is not applied");
            return null;
        }
        Filters scoped = compiler.compile(rules, ModelConstant.COMPANY_MODEL);
        if (scoped == null) {
            return null;
        }
        // Runs under the @SkipPermissionCheck that get() set for the whole build, so this read does not
        // re-enter the scope chain that is asking for the snapshot.
        List<Map<String, Object>> rows = modelService.searchList(ModelConstant.COMPANY_MODEL,
                new FlexQuery(List.of(ModelConstant.ID), scoped));
        Set<Long> grantedCompanyIds = new HashSet<>();
        for (Map<String, Object> row : rows) {
            if (row.get(ModelConstant.ID) instanceof Number n) {
                grantedCompanyIds.add(n.longValue());
            }
        }
        return grantedCompanyIds;
    }

    private PermissionInfo loadFromDb(Long tenantId, Long userId) {
        try {
            return doLoadFromDb(tenantId, userId);
        } catch (Throwable t) {
            // The reads throw when the RBAC models are absent (a standalone enforce
            // deployment without user-starter) — fail-closed. Such a deployment
            // supplies its own provider (RPC re-sourcer / keep-warm reader).
            log.warn("PermissionInfo build failed for user {} (tenant {}); fail-closed", userId, tenantId, t);
            return null;
        }
    }

    /** Package-private so the same-package test can drive the whole build — the consultant
     *  derivation and the routing it feeds only mean anything together. */
    PermissionInfo doLoadFromDb(Long tenantId, Long userId) {
        List<RoleView> activeRoles = loadActiveRolesFor(userId);
        Set<String> roleCodes = activeRoles.stream()
                .map(RoleView::getCode)
                .filter(c -> c != null && !c.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));
        if (isConsultantMembership(userId)) {
            roleCodes.add(BuiltinRole.CONSULTANT.getCode());
        }

        if (BuiltinRole.SUPER_ADMIN.heldBy(roleCodes)) {
            return platformAdminSnapshot(roleCodes);
        }
        if (BuiltinRole.anyHeldBy(roleCodes, BuiltinRole.TENANT_ADMIN, BuiltinRole.CONSULTANT)) {
            // A consultant gets the same MENU set a tenant admin does — everything the tenant's
            // plan entitles, derived here rather than stored as role grants. Stored grants would be
            // deleted by the entitlement cleanup on a downgrade and never restored, and the
            // consultant role is not editable or even visible in the tenant's role management, so
            // nobody could put them back: one downgrade would strip consultants permanently.
            // Derived, an upgrade takes effect the moment it is bought and a downgrade narrows on
            // its own. What separates the two principals is the DATA plane, which reads the role
            // code (PermissionInfo.hasFullDataAccess), not this set.
            return tenantAdminSnapshot(roleCodes, tenantId);
        }
        if (activeRoles.isEmpty()) {
            return emptyGrantsSnapshot(roleCodes);
        }
        List<Long> roleIds = activeRoles.stream().map(RoleView::getId).filter(Objects::nonNull).toList();
        if (roleIds.isEmpty()) {
            return emptyGrantsSnapshot(roleCodes);
        }

        // 3a. Navigation + permission grants.
        Set<String> navigations = new HashSet<>();
        Set<String> permissions = new HashSet<>();
        List<RoleNavigationView> navGrants = modelService.searchList(M_ROLE_NAV,
                new FlexQuery(List.of("navigationId", "permissionIds"), new Filters().in("roleId", roleIds)),
                RoleNavigationView.class);
        for (RoleNavigationView rn : navGrants) {
            if (rn.getNavigationId() == null) {
                continue;
            }
            navigations.add(rn.getNavigationId());
            List<String> pids = JsonUtils.toStringList(rn.getPermissionIds(), true);
            if (pids != null) {
                permissions.addAll(pids);
            }
        }

        // 3b. Row-scope grants, keyed by model.
        Map<String, List<ScopeRule>> modelScopeMap = new HashMap<>();
        List<RoleDataScopeView> scopeGrants = modelService.searchList(M_ROLE_SCOPE,
                new FlexQuery(List.of("model", "dataScopes"), new Filters().in("roleId", roleIds)),
                RoleDataScopeView.class);
        for (RoleDataScopeView rds : scopeGrants) {
            String model = rds.getModel();
            if (model == null || model.isBlank()) {
                continue;
            }
            List<ScopeRule> scopes = parseScopeRules(rds.getDataScopes());
            if (!scopes.isEmpty()) {
                modelScopeMap.computeIfAbsent(model, k -> new ArrayList<>()).addAll(scopes);
            }
        }

        // 3b-2. The company axis, derived from the scope just read for the company model.
        Set<Long> grantedCompanyIds = readGrantedCompanyIds(modelScopeMap);
        // 3c. Sensitive-field-set grants, keyed by the SFS's canonical model.
        Map<String, Set<String>> modelSensitiveFieldSetsMap = new HashMap<>();
        List<RoleSfsView> sfsGrants = modelService.searchList(M_ROLE_SFS,
                new FlexQuery(List.of("sensitiveFieldSetId"), new Filters().in("roleId", roleIds)),
                RoleSfsView.class);
        for (RoleSfsView g : sfsGrants) {
            String sid = g.getSensitiveFieldSetId();
            if (sid == null) {
                continue;
            }
            String sfsModel = sensitiveFieldSetCache.modelOf(sid);
            if (sfsModel == null) {
                continue;
            }
            modelSensitiveFieldSetsMap.computeIfAbsent(sfsModel, k -> new HashSet<>()).add(sid);
        }

        Set<String> expandedNavigations = expandAncestors(navigations);

        PermissionInfo info = new PermissionInfo();
        info.setRoleCodes(roleCodes);
        info.setNavigations(expandedNavigations);
        info.setPermissions(permissions);
        info.setModelScopeMap(modelScopeMap);
        info.setModelSensitiveFieldSetsMap(modelSensitiveFieldSetsMap);
        info.setGrantedCompanyIds(grantedCompanyIds);
        return info;
    }

    /**
     * True when the acting membership was minted for a consultant.
     *
     * <p>The consultant role code is <b>derived from the account, never stored as a role row</b>, for
     * the same two reasons the consultant's menus are derived. A real {@code Role} would show up in
     * the tenant's own role management, which the consultant role is explicitly not supposed to be
     * visible in — let alone editable. And the entitlement cleanup hard-deletes role grants on a plan
     * downgrade and never restores them: one downgrade would strip every consultant in that tenant
     * permanently, with no role left anywhere for anyone to put back.
     *
     * <p>Read generically by model name, like the RBAC reads around it — this module also gates
     * deployments that do not carry user-starter, and {@link #loadFromDb} fails closed when the model
     * is absent. One by-primary-key read, on the cache-miss path only.
     *
     * <p>{@code UiContextBuilder} asks the same question of the same column, and the two are NOT
     * shared. They cannot be without a type in the framework, which would put a business concept
     * there to save eight lines — and the rule being duplicated is "this column is true", which does
     * not drift: rename the column and both sides stop compiling. What went wrong once was the
     * ui-context build not asking at all, and nothing shared prevents forgetting to call something.
     */
    private boolean isConsultantMembership(Long userId) {
        if (userId == null) {
            return false;
        }
        List<Map<String, Object>> rows = modelService.searchList(M_USER_ACCOUNT,
                new FlexQuery(List.of(F_CONSULTANT), new Filters().eq(ModelConstant.ID, userId)));
        if (rows.isEmpty()) {
            return false;
        }
        Object flag = rows.get(0).get(F_CONSULTANT);
        // Boolean or 1/0, depending on how the driver maps the column.
        return Boolean.TRUE.equals(flag) || (flag instanceof Number n && n.intValue() == 1);
    }

    /** Roles for a user, filtered to active=true (inactive roles revoke their
     *  grants without deleting rows). */
    private List<RoleView> loadActiveRolesFor(Long userId) {
        List<UserRoleRelView> rels = modelService.searchList(M_USER_ROLE_REL,
                new FlexQuery(List.of("roleId"), new Filters().eq("userId", userId)),
                UserRoleRelView.class);
        if (rels.isEmpty()) {
            return List.of();
        }
        Set<Long> roleIds = rels.stream()
                .map(UserRoleRelView::getRoleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return modelService.searchList(M_ROLE,
                new FlexQuery(List.of("id", "code", "active"),
                        new Filters().in("id", roleIds).eq("active", true)),
                RoleView.class);
    }

    private Set<String> expandAncestors(Set<String> leafNavIds) {
        if (leafNavIds.isEmpty()) {
            return Collections.emptySet();
        }
        Map<String, List<String>> chains = ancestorChains();
        Set<String> out = new LinkedHashSet<>(leafNavIds);
        for (String leaf : leafNavIds) {
            List<String> chain = chains.get(leaf);
            if (chain == null || chain.isEmpty()) {
                continue;
            }
            for (String id : chain) {
                if (!id.equals(leaf)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /** Lazy leaf → ancestor-chain index, built from the Navigation tree; cached
     *  once non-empty (Navigation is seed data). */
    private Map<String, List<String>> ancestorChains() {
        Map<String, List<String>> cached = ancestorChains;
        if (cached != null) {
            return cached;
        }
        Map<String, List<String>> built = buildAncestorChains();
        if (!built.isEmpty()) {
            ancestorChains = built;
        }
        return built;
    }

    private Map<String, List<String>> buildAncestorChains() {
        List<NavigationView> all = modelService.searchList(M_NAV,
                new FlexQuery(List.of("id", "parentId"), new Filters()), NavigationView.class);
        if (all.isEmpty()) {
            return Map.of();
        }
        Map<String, NavigationView> byId = new HashMap<>(all.size());
        for (NavigationView n : all) {
            if (n.getId() != null) {
                byId.put(n.getId(), n);
            }
        }
        Map<String, List<String>> built = new HashMap<>(byId.size());
        for (NavigationView n : byId.values()) {
            String leafId = n.getId();
            List<String> chain = new ArrayList<>();
            chain.add(leafId);
            String cursor = n.getParentId();
            int guard = 0;
            Set<String> visited = new HashSet<>();
            visited.add(leafId);
            while (cursor != null && guard++ < ANCESTOR_DEPTH_CAP && visited.add(cursor)) {
                chain.add(cursor);
                NavigationView parent = byId.get(cursor);
                cursor = parent == null ? null : parent.getParentId();
            }
            Collections.reverse(chain);
            built.put(leafId, List.copyOf(chain));
        }
        return Map.copyOf(built);
    }

    /** Parse a {@code dataScopes} JSON array → ScopeRule list. Tolerant: skip
     *  rows missing scopeType or with an unknown enum value. */
    private static List<ScopeRule> parseScopeRules(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<ScopeRule> out = new ArrayList<>(node.size());
        for (JsonNode el : node) {
            if (!el.isObject()) {
                continue;
            }
            JsonNode typeNode = el.get("scopeType");
            if (typeNode == null || !typeNode.isString()) {
                continue;
            }
            ScopeType type;
            try {
                type = ScopeType.valueOf(typeNode.asString());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ScopeRule rule = new ScopeRule();
            rule.setScopeType(type);
            rule.setScopeExpr(el.get("scopeExpr"));
            out.add(rule);
        }
        return out;
    }

    /**
     * Tenant super-admin snapshot: every tenant-facing navigation (all navs minus the configured
     * platform-only prefixes), narrowed by the tenant's plan, + those navs' permissions. Scope / SFS
     * empty — a tenant admin bypasses row-scope and sees all fields within its own tenant.
     * Runtime-computed (mirrors user-starter's UiContextBuilder) so no static per-nav grants are needed
     * for the seeded TENANT_ADMIN role.
     *
     * <p><b>The plan narrowing is enforcement, not decoration.</b> It used to be left to the frontend,
     * on the reasoning that a downgrade strips the over-plan grants anyway. That reasoning does not
     * reach a tenant admin: it holds no static nav grants to strip, so the downgrade cleanup passes it
     * over and its access is whatever this method computes. Narrowing here is what makes the route gate
     * able to refuse a dropped module's endpoints — see PermissionInterceptor's tenant-admin branch,
     * which matches against exactly this permission set.
     */
    private PermissionInfo tenantAdminSnapshot(Set<String> roleCodes, Long tenantId) {
        List<NavigationView> allNavs = modelService.searchList(M_NAV,
                new FlexQuery(List.of("id"), new Filters()), NavigationView.class);
        // Resolved once, not per nav: the resolver is cache-aside, so a per-nav call would spend a
        // Redis round-trip on each of a hundred-odd navigations re-reading one unchanging set.
        Set<String> entitled = (entitlementService != null && tenantId != null)
                ? entitlementService.entitledModules(tenantId)
                : null;
        Set<String> navigations = new HashSet<>();
        for (NavigationView n : allNavs) {
            if (n.getId() != null && !isPlatformNav(n.getId()) && isEntitled(n.getId(), entitled)) {
                navigations.add(n.getId());
            }
        }
        List<PermissionView> allPerms = modelService.searchList(M_PERMISSION,
                new FlexQuery(List.of("id", "navigationId"), new Filters()), PermissionView.class);
        Set<String> permissions = new HashSet<>();
        for (PermissionView p : allPerms) {
            if (p.getId() != null && p.getNavigationId() != null && navigations.contains(p.getNavigationId())) {
                permissions.add(p.getId());
            }
        }
        PermissionInfo info = new PermissionInfo();
        info.setRoleCodes(roleCodes);
        info.setNavigations(navigations);
        info.setPermissions(permissions);
        info.setModelScopeMap(Collections.emptyMap());
        info.setModelSensitiveFieldSetsMap(Collections.emptyMap());
        return info;
    }

    /** Plan narrowing — mirrors the FE {@code navModuleOf} gating. Null set (no gate installed, or no
     *  tenant) → everything entitled. */
    private static boolean isEntitled(String navId, Set<String> entitled) {
        if (entitled == null) {
            return true;
        }
        String moduleId = NavIds.moduleOf(navId);
        return moduleId == null || entitled.contains(moduleId);
    }

    /** True when a nav id falls under a configured platform-only prefix (never tenant-facing). */
    private boolean isPlatformNav(String navId) {
        return matchesPrefix(navId, platformNavPrefixes);
    }

    /** What a platform administrator may reach: the platform's own modules plus the shared ones. */
    private boolean isPlatformAdminNav(String navId) {
        return isPlatformNav(navId) || matchesPrefix(navId, sharedNavPrefixes);
    }

    private static boolean matchesPrefix(String navId, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isBlank() && navId.startsWith(prefix.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Platform administrator: the platform's own navigations and nothing else.
     *
     * <p>The exact mirror of {@link #tenantAdminSnapshot} — that one takes everything EXCEPT the
     * platform prefixes, this one takes only them — so the two principals partition the product
     * between them and one config value decides where the line falls.
     *
     * <p>This used to be the empty-grants shape, which was safe only because the gate bypassed a
     * super-admin outright. That bypass is gone, and an empty permission set under a real gate
     * denies the platform administrator their own console. So the set is computed, for the same
     * reason a tenant admin's is: it holds no static grants, and what it may reach has to come from
     * somewhere.
     *
     * <p>No plan narrowing here, unlike the tenant admin's. A platform module is not something any
     * tenant buys, and there is no subscription on the platform's own tenant to read.
     *
     * <p>Scope and sensitive-field maps stay empty. This narrowing is about which SCREENS the
     * platform reaches;
     * cross-tenant reads — the account roster, provisioning — are what the platform administrator
     * exists to do, and are bounded by the endpoints above rather than by row scope.
     */
    private PermissionInfo platformAdminSnapshot(Set<String> roleCodes) {
        List<NavigationView> allNavs = modelService.searchList(M_NAV,
                new FlexQuery(List.of("id"), new Filters()), NavigationView.class);
        Set<String> navigations = new HashSet<>();
        for (NavigationView n : allNavs) {
            if (n.getId() != null && isPlatformAdminNav(n.getId())) {
                navigations.add(n.getId());
            }
        }
        List<PermissionView> allPerms = modelService.searchList(M_PERMISSION,
                new FlexQuery(List.of("id", "navigationId"), new Filters()), PermissionView.class);
        Set<String> permissions = new HashSet<>();
        for (PermissionView p : allPerms) {
            if (p.getId() != null && p.getNavigationId() != null && navigations.contains(p.getNavigationId())) {
                permissions.add(p.getId());
            }
        }
        PermissionInfo info = new PermissionInfo();
        info.setRoleCodes(roleCodes);
        // With ancestors, like the role-based build and like the ui-context assembly this mirrors.
        // A prefix such as `navigation.users.people.` admits the group's pages without the
        // `navigation.users` module row above them, and a caller asking about that row would be told
        // no by one build and yes by the other. The PERMISSIONS above are deliberately derived from
        // the unexpanded set: an ancestor is a container, not a screen anybody holds rights on.
        info.setNavigations(expandAncestors(navigations));
        info.setPermissions(permissions);
        info.setModelScopeMap(Collections.emptyMap());
        info.setModelSensitiveFieldSetsMap(Collections.emptyMap());
        return info;
    }

    private static PermissionInfo emptyGrantsSnapshot(Set<String> roleCodes) {
        PermissionInfo info = new PermissionInfo();
        info.setRoleCodes(roleCodes);
        info.setNavigations(Collections.emptySet());
        info.setPermissions(Collections.emptySet());
        info.setModelScopeMap(Collections.emptyMap());
        info.setModelSensitiveFieldSetsMap(Collections.emptyMap());
        return info;
    }

    // ─────────────────────── view DTOs (约定读 projections) ───────────────────────
    // Public + no-arg ctor (@Data): BeanTool.mapToObject instantiates via the
    // no-arg constructor and sets fields reflectively — records would NOT work.

    @Data public static class RoleView {
        private Long id;
        private String code;
        private Boolean active;
    }

    @Data public static class UserRoleRelView {
        private Long roleId;
    }

    @Data public static class RoleNavigationView {
        private String navigationId;
        private JsonNode permissionIds;
    }

    @Data public static class RoleDataScopeView {
        private String model;
        private JsonNode dataScopes;
    }

    @Data public static class RoleSfsView {
        private String sensitiveFieldSetId;
    }

    @Data public static class NavigationView {
        private String id;
        private String parentId;
    }

    @Data public static class PermissionView {
        private String id;
        private String navigationId;
    }
}
