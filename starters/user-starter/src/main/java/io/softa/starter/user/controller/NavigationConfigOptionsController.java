package io.softa.starter.user.controller;

import java.util.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.startup.StartupSnapshot;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.NavConfigOptions;
import io.softa.starter.user.dto.NavConfigOptions.SfsRef;
import io.softa.starter.user.dto.RoleModelConfigOption;
import io.softa.starter.user.service.DepartmentGrants;
import io.softa.starter.user.service.NavigationModelResolver;
import io.softa.starter.user.service.RoleGrantCeiling;
import io.softa.starter.user.util.JsonArrayUtils;

import static io.softa.framework.base.context.ContextUtils.inSystemContext;

/**
 * Wizard stage 3 endpoint: per-navigation options the FE wizard needs to
 * render — model label, applicable scope types, applicable sensitive field
 * sets, and any filter-related field options.
 *
 * <h3>Data sources (all in-memory, no per-request DB scan)</h3>
 * <ul>
 *   <li><b>primary model</b> — NavigationModelResolver (boot-loaded
 *       Navigation tree).</li>
 *   <li><b>applicable scopes</b> — ScopeApplicabilityResolver (model
 *       column metadata).</li>
 *   <li><b>applicable sensitive field sets</b> — SensitiveFieldSetCache
 *       (boot-loaded SFS table). Reading from cache avoids the per-request
 *       {@code modelService.searchList("SensitiveFieldSet", …)} that would
 *       otherwise be subject to {@code ScopeFilterAspect} — admins without
 *       an explicit scope grant on the {@code SensitiveFieldSet} model would
 *       see an empty SFS list (chicken-and-egg: the wizard is precisely
 *       where they would configure such grants).</li>
 * </ul>
 *
 * <p>Per-request cost is constant — a few HashMap lookups regardless of
 * total SFS / model count. The two registries this reads itself — the
 * sensitive field sets and the data scope types — are a {@link StartupSnapshot},
 * built once at startup after the seed files the build owns have loaded.
 *
 * <h3>Grant ceiling</h3>
 * The options are the same for every caller. What a non-administrator may actually grant is served
 * apart by {@code GET /userAccess/grantCeiling} — their own roles' grants, read the same way the save
 * judges them ({@link RoleGrantCeiling}) — so the wizard can grey out what the save would refuse.
 */
@Slf4j
@Tag(name = "Admin Navigation Config Options")
@RestController
@RequestMapping("/userAccess")
@RequiredArgsConstructor
public class NavigationConfigOptionsController implements StartupSnapshot {

    private final NavigationModelResolver navResolver;
    private final ModelService<?> modelService;
    private final RoleGrantCeiling roleGrantCeiling;

    /**
     * Relation kinds that trigger an INDEPENDENT query of the related model —
     * ManyToOne / ManyToMany are resolved via a dropdown picker / multi-select
     * that hits the related model's own {@code searchName} / {@code getById},
     * so the related model needs its own data scope. OneToOne / OneToMany
     * children are cascade-loaded under the parent row (bounded by the
     * parent's scope) and are intentionally NOT surfaced as separate models.
     */
    private static final Set<FieldType> LOOKUP_RELATION_TYPES =
            EnumSet.of(FieldType.MANY_TO_ONE, FieldType.MANY_TO_MANY);

    @GetMapping("/navigationConfigOptions")
    @Operation(summary = "Wizard stage 3 data — per-navigation scope + sensitive field set + filter field options")
    public ApiResponse<Map<String, NavConfigOptions>> getOptions(
            @RequestParam("navigation_ids") List<String> navigationIds) {
        if (navigationIds == null || navigationIds.isEmpty()) {
            return ApiResponse.success(Map.of());
        }
        // Dedup + preserve caller order — FE sends ids in render order; map
        // iteration mirrors that, which keeps wire-output stable for diffs.
        List<String> ordered = navigationIds.stream().distinct().toList();
        Map<String, NavConfigOptions> out = new LinkedHashMap<>();
        for (String navId : ordered) {
            out.put(navId, buildOne(navId));
        }
        return ApiResponse.success(out);
    }

    /**
     * Resolve a single nav. Returns null for GROUP / pure-container MENU
     * (no primary model) — FE renders the row collapsed in that case.
     */
    private NavConfigOptions buildOne(String navId) {
        String model = navResolver.resolvePrimaryModel(navId);
        if (model == null || !ModelManager.existModel(model)) {
            return null;
        }
        MetaModel meta = ModelManager.getModel(model);
        String label = meta != null && meta.getLabel() != null ? meta.getLabel() : model;

        // Applicable scope types = DataScopeType registry × model column shape.
        List<String> scopes = applicableScopeCodes(model);

        // SFS visible on this nav row = own (SFS.model == nav.model) ∪
        // attached (SFS.attachedTo contains nav.model). Dedup by setId so a
        // SFS that accidentally points its attachedTo at its own model shows up
        // once. Read from the SensitiveFieldSet table, in the system context (see sfs()).
        Set<String> seen = new HashSet<>();
        List<SfsRef> sfs = new ArrayList<>();
        for (String setId : sfsOwnedBy(model)) {
            if (seen.add(setId)) sfs.add(new SfsRef(setId, sfsName(setId)));
        }
        for (String setId : sfsAttachedTo(model)) {
            if (seen.add(setId)) sfs.add(new SfsRef(setId, sfsName(setId)));
        }

        return new NavConfigOptions(model, label, scopes, sfs);
    }

    // ─────────────────── what the caller may grant ───────────────────

    /**
     * The caller's own grants, role by role, for the wizard to grey out what a save would refuse: one role
     * has to hold an action and reach its rows, and a field set comes with the rows of the role granting it,
     * so the roles are not merged. An administrator is unrestricted.
     */
    @GetMapping("/grantCeiling")
    @Operation(summary = "Role wizard — what the caller may grant: their own roles' grants, or unrestricted for an administrator")
    public ApiResponse<GrantCeilingView> getGrantCeiling() {
        List<RoleGrantCeiling.Grants> grants = roleGrantCeiling.callerGrants();
        return ApiResponse.success(grants == null ? GrantCeilingView.UNRESTRICTED : new GrantCeilingView(false, grants));
    }

    /** The caller's roles' grants, or {@code unrestricted} when no ceiling applies. */
    public record GrantCeilingView(boolean unrestricted, List<RoleGrantCeiling.Grants> roles) {
        static final GrantCeilingView UNRESTRICTED = new GrantCeilingView(true, List.of());
    }

    // ─────────────────── per-model options (role wizard data step) ───────────────────

    @GetMapping("/roleModelConfigOptions")
    @Operation(summary = "Role wizard data step — per-model scope + SFS options for the granted navs' primary models AND their related (lookup) models")
    public ApiResponse<List<RoleModelConfigOption>> getModelOptions(
            @RequestParam("navigation_ids") List<String> navigationIds) {
        if (navigationIds == null || navigationIds.isEmpty()) {
            return ApiResponse.success(List.of());
        }
        // Primary models of the granted navs (dedup, keep caller order).
        Set<String> primary = new LinkedHashSet<>();
        for (String navId : navigationIds.stream().distinct().toList()) {
            String m = navResolver.resolvePrimaryModel(navId);
            if (m != null && ModelManager.existModel(m)) primary.add(m);
        }
        // Related/lookup models reachable via relational fields (L1 + L2),
        // mirroring EndpointIndex's lookup derivation — these get queried at
        // runtime (lookup / cascade), so the role needs a scope rule for them
        // too or they fail-closed to zero rows.
        Set<String> related = deriveRelatedModels(primary);

        List<RoleModelConfigOption> out = new ArrayList<>(primary.size() + related.size());
        for (String m : primary) out.add(buildModelOption(m, false));
        for (String m : related) out.add(buildModelOption(m, true));
        return ApiResponse.success(out);
    }

    /**
     * L1 (direct lookups) + L2 (lookups of those) related models, excluding the
     * primary set. Follows ONLY ManyToOne / ManyToMany relations (see
     * {@link #LOOKUP_RELATION_TYPES}) — those are queried independently via
     * pickers and therefore need their own scope. OneToOne / OneToMany children
     * are cascade-loaded under the parent's scope and are not listed.
     */
    private Set<String> deriveRelatedModels(Set<String> primary) {
        Set<String> visited = new HashSet<>(primary);
        Set<String> related = new LinkedHashSet<>();
        List<String> l1 = new ArrayList<>();
        for (String pm : primary) {
            for (String r : relationTargets(pm)) {
                if (visited.add(r)) {
                    related.add(r);
                    l1.add(r);
                }
            }
        }
        for (String parent : l1) {
            for (String r : relationTargets(parent)) {
                if (visited.add(r)) related.add(r);
            }
        }
        return related;
    }

    /** Distinct related-model names of a model's relational fields. Defensive:
     *  a corrupt / unknown model yields no targets rather than throwing. */
    private List<String> relationTargets(String model) {
        if (model == null || !ModelManager.existModel(model)) return List.of();
        List<MetaField> fields;
        try {
            fields = ModelManager.getModelFields(model);
        } catch (RuntimeException ex) {
            log.warn("roleModelConfigOptions — relation walk skipped for model {}: {}", model, ex.getMessage());
            return List.of();
        }
        if (fields == null) return List.of();
        List<String> out = new ArrayList<>();
        for (MetaField f : fields) {
            // ManyToOne / ManyToMany only — those get queried on their own via
            // pickers. OneToOne / OneToMany use the parent's scope (cascade).
            if (f.getFieldType() != null && LOOKUP_RELATION_TYPES.contains(f.getFieldType())) {
                String r = f.getRelatedModel();
                if (r != null && !r.isEmpty()) out.add(r);
            }
        }
        return out;
    }

    /** Per-model option row: label + applicable scopes + owned/attached SFS. */
    private RoleModelConfigOption buildModelOption(String model, boolean related) {
        MetaModel meta = ModelManager.getModel(model);
        String label = meta != null && meta.getLabel() != null ? meta.getLabel() : model;
        List<String> scopes = applicableScopeCodes(model);
        Set<String> seen = new HashSet<>();
        List<SfsRef> sfs = new ArrayList<>();
        for (String setId : sfsOwnedBy(model)) {
            if (seen.add(setId)) sfs.add(new SfsRef(setId, sfsName(setId)));
        }
        for (String setId : sfsAttachedTo(model)) {
            if (seen.add(setId)) sfs.add(new SfsRef(setId, sfsName(setId)));
        }
        return new RoleModelConfigOption(model, label, scopes, sfs, related, DepartmentGrants.bounds(model));
    }

    private String sfsName(String setId) {
        String name = sfs().nameById().get(setId);
        return name != null ? name : setId;
    }

    private Set<String> sfsOwnedBy(String model) {
        return sfs().ownedByModel().getOrDefault(model, Set.of());
    }

    private Set<String> sfsAttachedTo(String model) {
        return sfs().attachedByModel().getOrDefault(model, Set.of());
    }

    /** The {@code SensitiveFieldSet} table, indexed for the wizard; null until built. A platform catalogue
     *  that is the same for every caller, and the index is shared by all of them, so it is read in the
     *  system context: read as a role manager without a range on it, it came back empty. */
    private volatile SfsIndex sfsIndex;

    @Override
    public Set<String> sourceModels() {
        return Set.of("SensitiveFieldSet", "DataScopeType");
    }

    /** Read both registries the wizard works from: the sensitive field sets and the data scope types. */
    @Override
    public void build() {
        sfsIndex = loadSfsIndex();
        // The registry is the same for every caller and the snapshot is shared, so it is read in the system
        // context: read as a role manager without a range on it, every model offered All alone.
        scopeTypes = inSystemContext(() ->
                modelService.searchList("DataScopeType", new FlexQuery(), DataScopeTypeView.class));
    }

    private SfsIndex sfs() {
        SfsIndex built = sfsIndex;
        if (built == null) {
            throw notBuilt();
        }
        return built;
    }

    private static IllegalStateException notBuilt() {
        return new IllegalStateException("The navigation wizard's registries are read before they are built: they "
                + "are built at startup, once the seed files the build owns have loaded, and nothing may ask them "
                + "before.");
    }

    private SfsIndex loadSfsIndex() {
        List<SensitiveFieldSetView> rows = inSystemContext(() ->
                modelService.searchList("SensitiveFieldSet", new FlexQuery(), SensitiveFieldSetView.class));
        Map<String, Set<String>> owned = new HashMap<>();
        Map<String, Set<String>> attached = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (SensitiveFieldSetView r : rows) {
            String id = r.getId();
            if (id == null) {
                continue;
            }
            if (r.getName() != null) {
                names.put(id, r.getName());
            }
            if (r.getModel() != null) {
                owned.computeIfAbsent(r.getModel(), k -> new HashSet<>()).add(id);
            }
            List<String> att = JsonArrayUtils.toStringList(r.getAttachedTo());
            if (att != null) {
                for (String a : att) {
                    if (a != null && !a.isEmpty()) {
                        attached.computeIfAbsent(a, k -> new HashSet<>()).add(id);
                    }
                }
            }
        }
        return new SfsIndex(Map.copyOf(owned), Map.copyOf(attached), Map.copyOf(names));
    }

    private record SfsIndex(Map<String, Set<String>> ownedByModel,
                            Map<String, Set<String>> attachedByModel,
                            Map<String, String> nameById) {
    }

    // ─────────────────── scope applicability (data-driven) ───────────────────

    /** The {@code DataScopeType} registry, whose rows change only at startup;
     *  null until built. */
    private volatile List<DataScopeTypeView> scopeTypes;

    /**
     * Applicable scope-type codes for {@code model}, derived from the
     * {@code DataScopeType} registry × the model's field shape — the same rule
     * {@code ScopeApplicabilityResolver} applies on the enforce side, computed
     * here from data so this wizard needs no dependency on the permission engine.
     * {@code "ALL"} is always included; a type set at role level is offered on no model.
     */
    private List<String> applicableScopeCodes(String model) {
        Set<String> out = new LinkedHashSet<>();
        out.add("ALL");
        Set<String> fields = new HashSet<>();
        if (model != null && ModelManager.existModel(model)) {
            for (MetaField mf : ModelManager.getModelFields(model)) {
                fields.add(mf.getFieldName());
            }
        }
        for (DataScopeTypeView r : scopeTypes()) {
            if (r.getId() != null && !Boolean.TRUE.equals(r.getRoleLevelOnly()) && r.matches(model, fields)) {
                out.add(r.getId());
            }
        }
        return out.stream().sorted().toList();
    }

    private List<DataScopeTypeView> scopeTypes() {
        List<DataScopeTypeView> built = scopeTypes;
        if (built == null) {
            throw notBuilt();
        }
        return built;
    }

    // ─────────────────── view DTOs (约定读 projections) ───────────────────
    // Public + no-arg ctor (@Data): ModelService's Class projection (BeanTool)
    // instantiates via the no-arg constructor + reflective field-set — records
    // would NOT work.

    /**
     * {@code DataScopeType} projection + its applicability rule. Kept intentionally
     * in sync with the engine's {@code ScopeApplicabilityResolver.Rule} (they read the
     * same rows by name); user-starter can't import the engine (⊥), so the rule is
     * replicated here over softa-orm {@code Filters} only.
     *
     * <p>Identity types carry a {@code filter} template — applicability is derived
     * from its field references (and {@code identityFilter}'s for the {@code
     * identityModel} model-swap). Code-contributor types (no filter) use the explicit
     * {@code applicableFields}.
     */
    @Data
    public static class DataScopeTypeView {
        private String id;
        private Boolean appliesToAll;
        private List<String> applicableFields;
        private JsonNode filter;
        private String identityModel;
        private JsonNode identityFilter;
        private Boolean roleLevelOnly;

        boolean matches(String model, Set<String> fields) {
            if (Boolean.TRUE.equals(appliesToAll)) {
                return true;
            }
            if (filter != null && !filter.isEmpty()) {
                // Mirror the engine's ScopeFilterTemplates.forModel exactly: on the identity model
                // its own template; elsewhere the business filter; failing that, the identity
                // model's own anchor, on the row or through the employee it belongs to. An anchor
                // on id says nothing, as every model has one.
                if (identityModel != null && identityModel.equals(model)) {
                    return !fieldRefs(identityFilter).isEmpty() && fields.containsAll(fieldRefs(identityFilter));
                }
                Set<String> own = fieldRefs(filter);
                if (!own.isEmpty() && fields.containsAll(own)) {
                    return true;
                }
                Set<String> anchors = identityModel == null ? Set.of() : fieldRefs(identityFilter);
                if (anchors.isEmpty() || anchors.contains("id")) {
                    return false;
                }
                return fields.containsAll(anchors) || belongsToIdentityModel(model);
            }
            if (applicableFields == null) {
                return false;
            }
            for (String f : applicableFields) {
                if (fields.contains(f)) {
                    return true;
                }
            }
            return false;
        }

        private boolean belongsToIdentityModel(String model) {
            MetaField owner = model == null ? null : ModelManager.getModelFieldOrNull(model, "employeeId");
            return owner != null && FieldType.TO_ONE_TYPES.contains(owner.getFieldType())
                    && identityModel.equals(owner.getRelatedModel());
        }

        /** LHS field names referenced by a filter template (never the RHS env values). */
        private static Set<String> fieldRefs(JsonNode template) {
            if (template == null || template.isEmpty()) {
                return Set.of();
            }
            Set<String> out = new HashSet<>();
            try {
                collectFields(Filters.of(template.toString()), out);
            } catch (RuntimeException ignored) {
                // malformed template → no fields → type simply won't be offered
            }
            return out;
        }

        private static void collectFields(Filters f, Set<String> out) {
            if (f == null) {
                return;
            }
            FilterUnit u = f.getFilterUnit();
            if (u != null) {
                if (u.getField() != null && !u.getField().isBlank()) {
                    out.add(u.getField());
                }
                if (u.getFields() != null) {
                    out.addAll(u.getFields());
                }
            }
            for (Filters child : f.getChildren()) {
                collectFields(child, out);
            }
        }
    }

    /** {@code SensitiveFieldSet} projection — {@code attachedTo} mirrors the
     *  entity's JsonNode column. */
    @Data
    public static class SensitiveFieldSetView {
        private String id;
        private String model;
        private String name;
        private JsonNode attachedTo;
    }
}
