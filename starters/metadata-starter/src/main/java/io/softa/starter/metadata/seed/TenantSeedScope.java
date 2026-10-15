package io.softa.starter.metadata.seed;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.EntitlementService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.entity.SysPreData;
import io.softa.starter.metadata.service.SysPreDataService;

import static io.softa.starter.metadata.seed.SyncSupport.asSystem;

/**
 * Which tenant seed files a tenant is due, and which it holds.
 *
 * <p>Due: the files of the packages its plan entitles — a package with no module is for every plan — for
 * its countries, which are its default country and the countries of its companies. Held: the files it has
 * rows from, by the file its bindings record. A sync reaches a tenant with the files it is due or holds, so
 * a tenant that moved to a smaller plan keeps receiving what is added to the packages it already has —
 * hidden by its entitlement — and has nothing to catch up on when it moves back. A file it is due and does
 * not hold is one it never had: that one is loaded whole.
 */
@Service
public class TenantSeedScope {

    private static final String TENANT_MODEL = "TenantInfo";
    /** Key prefix of a retired preId in {@link #fileOfRowKey}: it is listed without its model. */
    public static final String RETIRED_KEY_PREFIX = "*/";
    private static final String PLAN_ENTITLEMENT_MODEL = "PlanEntitlement";

    /** Per level, the file declaring each row. The classpath does not change while the application runs. */
    private final Map<SeedLevel, Map<String, String>> fileOfRowKey = new ConcurrentHashMap<>();

    private final ObjectProvider<SeedManifest> manifestProvider;
    private final ObjectProvider<EntitlementService> entitlementService;
    private final ModelService<?> modelService;
    private final SysPreDataService preDataService;

    public TenantSeedScope(ObjectProvider<SeedManifest> manifestProvider,
                           ObjectProvider<EntitlementService> entitlementService,
                           ModelService<?> modelService,
                           SysPreDataService preDataService) {
        this.manifestProvider = manifestProvider;
        this.entitlementService = entitlementService;
        this.modelService = modelService;
        this.preDataService = preDataService;
    }

    /** The tenant files the tenant is due, in load order. */
    public List<String> dueFiles(Long tenantId) {
        return manifestProvider.getObject().tenantFiles(modulesOf(tenantId), countriesOf(tenantId));
    }

    /** The tenant files the tenant has rows from. */
    public Set<String> heldFiles(Long tenantId) {
        return asSystem(() -> new LinkedHashSet<>(preDataService.getDistinctFieldValue(SysPreData::getSourceFile,
                new Filters().eq(SysPreData::getTenantId, tenantId).isSet(SysPreData::getSourceFile))));
    }

    /** The files a sync reaches the tenant with: those it is due and those it holds. */
    public Set<String> reachedFiles(Long tenantId) {
        Set<String> reached = new LinkedHashSet<>(dueFiles(tenantId));
        reached.addAll(heldFiles(tenantId));
        return reached;
    }

    /**
     * The files the tenant is due and never had, in load order — to be loaded whole. Empty for a tenant
     * whose bindings do not record their file yet, or that has none: every file would look never had, and
     * loading them whole would fill every gap the tenant has had since it was set up.
     */
    public List<String> missingFiles(Long tenantId) {
        if (!recordsSources(tenantId)) {
            return List.of();
        }
        Set<String> held = heldFiles(tenantId);
        return dueFiles(tenantId).stream().filter(file -> !held.contains(file)).toList();
    }

    /**
     * Whether the tenant has bindings and every one of them records the file it came from. A tenant with
     * none was set up before its seed data was recorded at all, which tells nothing about what it has.
     */
    public boolean recordsSources(Long tenantId) {
        return asSystem(() -> preDataService.exist(
                        new Filters().eq(SysPreData::getTenantId, tenantId).isSet(SysPreData::getSourceFile))
                && !preDataService.exist(
                        new Filters().eq(SysPreData::getTenantId, tenantId).isNotSet(SysPreData::getSourceFile)));
    }

    /**
     * The modules the tenant's plan entitles, or null when entitlement is not in use — every package is
     * then due.
     */
    public Set<String> modulesOf(Long tenantId) {
        EntitlementService entitlement = entitlementService.getIfAvailable();
        return entitlement == null ? null : entitlement.entitledModules(tenantId);
    }

    /** The tenant's default country and the countries of its companies. */
    public Set<String> countriesOf(Long tenantId) {
        Set<String> countries = new LinkedHashSet<>();
        if (ModelManager.existModel(TENANT_MODEL)) {
            asSystem(() -> modelService.searchList(TENANT_MODEL,
                            new FlexQuery(List.of(ModelConstant.ID, "defaultCountry"), new Filters().eq(ModelConstant.ID, tenantId)))
                    .forEach(row -> addCode(countries, row.get("defaultCountry"))));
        }
        if (ModelManager.existModel(ModelConstant.COMPANY_MODEL)) {
            FlexQuery query = new FlexQuery(List.of(ModelConstant.COUNTRY_FIELD), new Filters());
            query.setDistinct(true);
            ContextUtils.inTenantContext(tenantId, () -> modelService.searchList(ModelConstant.COMPANY_MODEL, query)
                    .forEach(row -> addCode(countries, row.get(ModelConstant.COUNTRY_FIELD))));
        }
        return countries;
    }

    /**
     * The packages a tenant on this plan in this country would be given, with their files — what a new
     * tenant is set up with.
     */
    public List<SeedPackagePreview> preview(String planId, String country) {
        SeedManifest manifest = manifestProvider.getObject();
        Set<String> modules = planModules(planId);
        List<String> files = manifest.tenantFiles(modules, country == null ? Set.of() : Set.of(country));
        Map<String, List<String>> byPackage = new LinkedHashMap<>();
        for (String name : files) {
            SeedFile file = manifest.file(name).orElseThrow();
            byPackage.computeIfAbsent(file.packageKey(), k -> new ArrayList<>()).add(name);
        }
        return manifest.packages().stream()
                .filter(p -> byPackage.containsKey(p.key()))
                .map(p -> new SeedPackagePreview(p.key(), p.name(), p.module(), byPackage.get(p.key())))
                .toList();
    }

    /**
     * The packages the tenant is reached with, with their files: those it is due, then those it only still
     * holds — in manifest order either way.
     */
    public List<TenantSeedPackage> packagesOf(Long tenantId) {
        SeedManifest manifest = manifestProvider.getObject();
        Set<String> due = new LinkedHashSet<>(dueFiles(tenantId));
        Set<String> reached = new LinkedHashSet<>(due);
        reached.addAll(heldFiles(tenantId));
        Map<String, List<String>> byPackage = new LinkedHashMap<>();
        Set<String> entitled = new LinkedHashSet<>();
        for (String name : manifest.loadOrder(SeedLevel.TENANT)) {
            if (!reached.contains(name)) {
                continue;
            }
            SeedFile file = manifest.file(name).orElseThrow();
            byPackage.computeIfAbsent(file.packageKey(), k -> new ArrayList<>()).add(name);
            if (due.contains(name)) {
                entitled.add(file.packageKey());
            }
        }
        return manifest.packages().stream()
                .filter(p -> byPackage.containsKey(p.key()))
                .map(p -> new TenantSeedPackage(p.key(), p.name(), p.module(), byPackage.get(p.key()),
                        entitled.contains(p.key())))
                .sorted(Comparator.comparing(p -> !p.entitled()))
                .toList();
    }

    /**
     * The file of this level that declares each row, keyed {@code Model/preId} — its rows and the rows nested
     * in them — and {@code * /preId} (no space) for a preId a file lists as retired, whatever its model. A row
     * two files declare belongs to the first in load order.
     */
    public Map<String, String> fileOfRowKey(SeedLevel level) {
        return fileOfRowKey.computeIfAbsent(level, l -> {
            SeedManifest manifest = manifestProvider.getObject();
            Map<String, String> files = new LinkedHashMap<>();
            for (String name : manifest.loadOrder(l)) {
                preDataService.rowKeysOf(l.getDataDir(), name).forEach(key -> files.putIfAbsent(key, name));
                manifest.file(name).ifPresent(file ->
                        file.retired().forEach(preId -> files.putIfAbsent(RETIRED_KEY_PREFIX + preId, name)));
            }
            return Map.copyOf(files);
        });
    }

    /** Each of these tenants' code, for the records that name them; empty when the app has no tenant model. */
    public Map<Long, String> tenantCodes(Collection<Long> tenantIds) {
        Map<Long, String> codes = new LinkedHashMap<>();
        tenantRows(tenantIds).forEach((id, row) -> codes.put(id, Objects.toString(row.get("code"), null)));
        return codes;
    }

    private Map<Long, Map<String, Object>> tenantRows(Collection<Long> tenantIds) {
        Map<Long, Map<String, Object>> tenants = new LinkedHashMap<>();
        if (ModelManager.existModel(TENANT_MODEL) && !tenantIds.isEmpty()) {
            asSystem(() -> modelService.searchList(TENANT_MODEL, new FlexQuery(List.of(ModelConstant.ID, "code", "name"),
                            new Filters().in(ModelConstant.ID, List.copyOf(tenantIds)))))
                    .forEach(row -> tenants.put(Long.valueOf(String.valueOf(row.get(ModelConstant.ID))), row));
        }
        return tenants;
    }

    /**
     * What tracing each of these tenants would do: its bindings without a file, the files it is due and has
     * nothing from, and the rows of its due files it has no binding for.
     */
    public List<TenantTracePreview> tracePreview(List<Long> tenantIds) {
        Map<String, List<String>> keysOfFile = new LinkedHashMap<>();
        fileOfRowKey(SeedLevel.TENANT).forEach((key, file) -> {
            if (!key.startsWith(RETIRED_KEY_PREFIX)) {
                keysOfFile.computeIfAbsent(file, k -> new ArrayList<>()).add(key);
            }
        });
        Map<Long, Map<String, Object>> tenants = tenantRows(tenantIds);
        List<TenantTracePreview> previews = new ArrayList<>();
        for (Long tenantId : tenantIds) {
            List<SysPreData> bindings = asSystem(() -> preDataService.searchList(
                    new Filters().eq(SysPreData::getTenantId, tenantId)));
            Set<String> bound = new HashSet<>();
            long untraced = 0;
            for (SysPreData binding : bindings) {
                bound.add(binding.getModel() + "/" + binding.getPreId());
                if (binding.getSourceFile() == null) {
                    untraced++;
                }
            }
            List<String> neverHad = new ArrayList<>();
            int withoutBinding = 0;
            for (String file : dueFiles(tenantId)) {
                List<String> keys = keysOfFile.getOrDefault(file, List.of());
                long missing = keys.stream().filter(key -> !bound.contains(key)).count();
                if (!keys.isEmpty() && missing == keys.size()) {
                    neverHad.add(file);
                }
                withoutBinding += (int) missing;
            }
            Map<String, Object> tenant = tenants.getOrDefault(tenantId, Map.of());
            previews.add(new TenantTracePreview(tenantId, Objects.toString(tenant.get("code"), null),
                    Objects.toString(tenant.get("name"), null), untraced, neverHad, withoutBinding));
        }
        return previews;
    }

    /** The modules a plan entitles, or null when plans are not in use. */
    private Set<String> planModules(String planId) {
        if (!ModelManager.existModel(PLAN_ENTITLEMENT_MODEL)) {
            return null;
        }
        if (planId == null) {
            return Set.of();
        }
        return asSystem(() -> {
            Set<String> modules = new LinkedHashSet<>();
            modelService.searchList(PLAN_ENTITLEMENT_MODEL,
                            new FlexQuery(List.of("moduleId"), new Filters().eq("planId", planId)))
                    .forEach(row -> addCode(modules, row.get("moduleId")));
            return modules;
        });
    }

    /** A reference's code: the value itself, or the id of a reference read as a map. */
    private static void addCode(Set<String> into, Object value) {
        Object code = value instanceof Map<?, ?> reference ? reference.get(ModelConstant.ID) : value;
        if (code instanceof Serializable && !Objects.toString(code, "").isBlank()) {
            into.add(code.toString().trim());
        }
    }
}
