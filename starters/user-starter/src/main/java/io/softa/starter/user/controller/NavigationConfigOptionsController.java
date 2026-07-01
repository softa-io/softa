package io.softa.starter.user.controller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.NavConfigOptions;
import io.softa.starter.user.dto.NavConfigOptions.SfsRef;
import io.softa.starter.user.enums.ScopeType;
import io.softa.starter.user.filter.SensitiveFieldSetCache;
import io.softa.starter.user.scope.ScopeApplicabilityResolver;
import io.softa.starter.user.service.NavigationModelResolver;

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
 * Current policy: only super-admin can hit this endpoint (Layer A gate on
 * {@code /admin/*} wizard endpoints). Every caller here therefore sees the
 * full option set for each nav — no per-editor filtering. If non-super-admin
 * role editing is reintroduced, restore {@code GrantCeilingValidator} to
 * trim scope / SFS / nav options per editor's own grants (Known-Issues C3).
 */
@Slf4j
@Tag(name = "Admin Navigation Config Options")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class NavigationConfigOptionsController {

    private final NavigationModelResolver navResolver;
    private final ScopeApplicabilityResolver scopeApplicability;
    private final SensitiveFieldSetCache sfsCache;

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
        String label = meta != null && meta.getLabelName() != null ? meta.getLabelName() : model;

        // Applicable scope types = model-column-shape-derived.
        List<String> scopes = scopeApplicability.applicableFor(model).stream()
                .map(ScopeType::name)
                .sorted()
                .toList();

        // SFS visible on this nav row = own (SFS.model == nav.model) ∪
        // attached (SFS.attachedTo contains nav.model). Dedup by setId so a
        // SFS that accidentally points its attachedTo at its own model
        // shows up only once. All data comes from the in-memory cache.
        Set<String> seen = new HashSet<>();
        List<SfsRef> sfs = new ArrayList<>();
        for (String setId : sfsCache.setIdsOwnedBy(model)) {
            if (!seen.add(setId)) continue;
            String name = sfsCache.nameOf(setId);
            sfs.add(new SfsRef(setId, name != null ? name : setId));
        }
        for (String setId : sfsCache.setIdsAttachedTo(model)) {
            if (!seen.add(setId)) continue;
            String name = sfsCache.nameOf(setId);
            sfs.add(new SfsRef(setId, name != null ? name : setId));
        }

        return new NavConfigOptions(model, label, scopes, sfs);
    }
}
