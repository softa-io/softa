package io.softa.starter.user.controller;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.NavConfigOptions;
import io.softa.starter.user.dto.NavConfigOptions.SfsRef;
import io.softa.starter.user.dto.RoleModelConfigOption;
import io.softa.starter.user.service.NavigationModelResolver;
import io.softa.starter.user.util.JsonArrayUtils;

/**
 * Wizard stage 3 endpoint: per-navigation options the FE wizard needs to
 * render — model label, applicable scope types, applicable sensitive field
 * sets, and any filter-related field options.
 *
 * <h3>Data sources (all in-memory, no per-request DB scan)</h3>
 * <ul>
 *   <li><b>primary model</b> — {@link NavigationModelResolver} (boot-loaded
 *       Navigation tree).</li>
 *   <li><b>applicable scopes</b> — {@link ScopeApplicabilityResolver} (model
 *       column metadata).</li>
 *   <li><b>applicable sensitive field sets</b> — {@link SensitiveFieldSetCache}
 *       (boot-loaded SFS table). Reading from cache avoids the per-request
 *       {@code modelService.searchList("SensitiveFieldSet", …)} that would
 *       otherwise be subject to {@code ScopeFilterAspect} — admins without
 *       an explicit scope grant on the {@code SensitiveFieldSet} model would
 *       see an empty SFS list (chicken-and-egg: the wizard is precisely
 *       where they would configure such grants).</li>
 * </ul>
 *
 * <p>Per-request cost is constant — a few HashMap lookups regardless of
 * total SFS / model count.
 *
 * <h3>Grant ceiling</h3>
 * Current policy: only super-admin can hit this endpoint (endpoint-gate
 * check on {@code /userAccess/*} wizard endpoints). Every caller here therefore
 * sees the full option set for each nav — no per-editor filtering. If
 * non-super-admin role editing is reintroduced, restore
 * {@code GrantCeilingValidator} to trim scope / SFS / nav options per
 * editor's own grants (grant-ceiling gap).
 */
@Slf4j
@Tag(name = "Admin Navigation Config Options")
@RestController
@RequestMapping("/userAccess")
@RequiredArgsConstructor
public class NavigationConfigOptionsController {

    private final NavigationModelResolver navResolver;
    private final ModelService<?> modelService;

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
        // once. Read from the SensitiveFieldSet table (约定读; super-admin only).
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
        // runtime (lookup / cascade). Their rule decides which records the
        // role's dropdowns offer; the role sees those records' labels only.
        // Without a rule, only the records the role's own rows point at.
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
        return new RoleModelConfigOption(model, label, scopes, sfs, related);
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

    /** Lazy cache of the {@code SensitiveFieldSet} table, indexed for the wizard.
     *  Replaces the permission-starter {@code SensitiveFieldSetCache}; only
     *  super-admins reach this endpoint, so the read is not scope-filtered. */
    private volatile SfsIndex sfsIndex;

    private SfsIndex sfs() {
        SfsIndex idx = sfsIndex;
        if (idx != null) {
            return idx;
        }
        List<SensitiveFieldSetView> rows =
                modelService.searchList("SensitiveFieldSet", new FlexQuery(), SensitiveFieldSetView.class);
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
        SfsIndex built = new SfsIndex(Map.copyOf(owned), Map.copyOf(attached), Map.copyOf(names));
        sfsIndex = built;
        return built;
    }

    private record SfsIndex(Map<String, Set<String>> ownedByModel,
                            Map<String, Set<String>> attachedByModel,
                            Map<String, String> nameById) {
    }

    // ─────────────────── scope applicability (data-driven) ───────────────────

    /** Lazy cache of the {@code DataScopeType} registry (seed data; changes on
     *  redeploy). */
    private volatile List<DataScopeTypeView> scopeTypes;

    /**
     * Applicable scope-type codes for {@code model}, derived from the
     * {@code DataScopeType} registry × the model's field shape — the same rule
     * {@code ScopeApplicabilityResolver} applies on the enforce side, computed
     * here from data so this wizard needs no dependency on the permission engine.
     * {@code "ALL"} is always included. Only super-admins reach this endpoint, so
     * the {@code DataScopeType} read is not scope-filtered.
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
            if (r.getId() != null && r.matches(model, fields)) {
                out.add(r.getId());
            }
        }
        return out.stream().sorted().toList();
    }

    private List<DataScopeTypeView> scopeTypes() {
        List<DataScopeTypeView> cached = scopeTypes;
        if (cached != null) {
            return cached;
        }
        List<DataScopeTypeView> loaded =
                modelService.searchList("DataScopeType", new FlexQuery(), DataScopeTypeView.class);
        if (!loaded.isEmpty()) {
            scopeTypes = loaded;
        }
        return loaded;
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

        boolean matches(String model, Set<String> fields) {
            if (Boolean.TRUE.equals(appliesToAll)) {
                return true;
            }
            if (filter != null && !filter.isEmpty()) {
                // Mirror the engine's ScopeApplicabilityResolver.Rule exactly: on the
                // identity model use identityFilter (no fallback to the business filter,
                // whose anchor doesn't exist there); elsewhere the business filter.
                Set<String> anchors = (identityModel != null && identityModel.equals(model))
                        ? fieldRefs(identityFilter) : fieldRefs(filter);
                return !anchors.isEmpty() && fields.containsAll(anchors);
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
