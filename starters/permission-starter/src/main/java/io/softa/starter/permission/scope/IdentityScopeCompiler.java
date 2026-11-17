package io.softa.starter.permission.scope;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.constant.EnvConstant;
import io.softa.framework.base.context.EmpInfo;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.startup.StartupSnapshot;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;

/**
 * Data-driven compiler for the "identity" scope types — the ones whose row filter
 * is a fixed template of the shape {@code <field> = <an env value from the caller's
 * identity context>} (SELF / DIRECT_REPORTS / CREATED_BY_SELF).
 *
 * <h3>Everything comes from the DataScopeType row (2026-07-17 filter-merge)</h3>
 * A {@code DataScopeType} row is an identity type iff it declares a {@code filter}
 * template (a Filters-shape JSON whose leaf values use env placeholders — the same
 * {@link EnvConstant} tokens {@code CustomScopeContributor} uses, e.g.
 * {@code USER_EMP_ID} / {@code USER_ID} / {@code USER_COMP_ID}). The compiler just
 * emits that template as a {@link Filters}; the actual value substitution is
 * <b>deferred to {@code FilterUnitParser} at SQL-build time</b> — exactly how CUSTOM
 * scopes resolve their env placeholders. This reuses the framework's single env
 * resolver instead of a bespoke {@code principalSource → value} switch.
 *
 * <h3>Model-swap</h3>
 * When the queried model is the {@code identityModel} (the identity entity's own
 * table, which has no FK to itself), the {@code identityFilter} template is used
 * instead — e.g. SELF filters {@code employeeId = USER_EMP_ID} on business models
 * but {@code id = USER_EMP_ID} on {@code Employee} itself.
 *
 * <h3>Fail-closed</h3>
 * Value substitution is deferred, but if a required identity value is absent the
 * compiler short-circuits to {@code new Filters()} ("this rule contributes no
 * rows"): a template with an {@code EMP_INFO} token and no {@link EmpInfo} bound
 * (which would otherwise make {@code FilterUnitParser} throw), or a {@code USER_ID}
 * token with no userId. The check is object-presence only, keyed on the framework's
 * {@code EnvConstant} sets — the value itself (and any null-field {@code = NULL}
 * handling) stays with {@code FilterUnitParser}.
 *
 * <h3>What stays code</h3>
 * Hierarchical types needing a runtime DB lookup (DEPT_SUBTREE / MANAGED_DEPARTMENTS)
 * and the generic CUSTOM evaluator keep their own {@code ScopeContributor}s.
 *
 * <p>A {@link StartupSnapshot}: the rows are read once at startup, once the seed
 * files the build owns have loaded. A fresh {@link Filters} is built per call (never
 * a cached instance) so the downstream OR-merge can't mutate the snapshot.
 */
@Slf4j
@Component
public class IdentityScopeCompiler implements StartupSnapshot {

    private final DataScopeTypeReader reader;
    private volatile Map<String, Spec> specs;

    public IdentityScopeCompiler(DataScopeTypeReader reader) {
        this.reader = reader;
    }

    @Override
    public Set<String> sourceModels() {
        return Set.of(DataScopeTypeReader.MODEL);
    }

    /** Read the identity specs: the DataScopeType rows carrying a {@code filter} template. */
    @Override
    public void build() {
        Map<String, Spec> loaded = new HashMap<>();
        for (Map<String, Object> m : reader.read()) {
            Object filter = m.get("filter");
            if (filter == null) {
                continue;   // not an identity type (code contributor / ALL / CUSTOM)
            }
            loaded.put(str(m.get("id")), new Spec(
                    str(m.get("identityModel")), filter, m.get("identityFilter")));
        }
        specs = Map.copyOf(loaded);
    }

    /** True if {@code type} is a data-driven identity scope (its DataScopeType row
     *  declares a {@code filter} template) — this compiler handles it, no
     *  {@code ScopeContributor} needed. */
    public boolean handles(ScopeType type) {
        return type != null && specs().containsKey(type.name());
    }

    /**
     * Compile an identity rule into a {@link Filters} (env placeholders unresolved —
     * {@code FilterUnitParser} resolves them at SQL time), or {@code null} when
     * {@code type} is not a data-driven identity type (caller falls through). Returns
     * {@code new Filters()} (fail-closed) when the template needs an EmpInfo value
     * the caller doesn't carry.
     */
    public Filters compile(ScopeType type, ScopeRule rule, String modelName) {
        if (type == null) {
            return null;
        }
        Spec s = specs().get(type.name());
        if (s == null) {
            return null;
        }

        // The template this model takes, as ScopeApplicabilityResolver reads it, so
        // applicability ⇔ compilability stays exact. A model the catalog does not know keeps
        // the type's own template: nothing can be said about its fields.
        Filters f = ModelManager.existModel(modelName)
                ? ScopeFilterTemplates.forModel(modelName, fieldNames(modelName), s.identityModel(),
                        s.filter(), s.identityFilter())
                : ScopeFilterTemplates.forModel(modelName, ScopeFilterTemplates.fieldRefs(s.filter()),
                        s.identityModel(), s.filter(), s.identityFilter());
        if (f == null) {
            return new Filters();   // fail-closed
        }

        // Value substitution is deferred to FilterUnitParser, but if a required
        // identity value is absent we must fail closed here — both to preserve the
        // prior "no principal → no rows" behaviour and to avoid FilterUnitParser
        // throwing on an EMP_INFO token with no EmpInfo bound. Object-presence only
        // (keyed on the framework's EnvConstant sets — no value switch); the actual
        // value + any null-field handling stays with FilterUnitParser.
        if (!ScopeEnvGuard.contextSatisfies(f)) {
            return new Filters();
        }
        return f;
    }

    private static Set<String> fieldNames(String modelName) {
        Set<String> names = new HashSet<>();
        for (MetaField field : ModelManager.getModelFields(modelName)) {
            names.add(field.getFieldName());
        }
        return names;
    }

    private Map<String, Spec> specs() {
        Map<String, Spec> built = specs;
        if (built == null) {
            throw new IllegalStateException("IdentityScopeCompiler is read before it is built: it is built at "
                    + "startup, once the seed files the build owns have loaded, and nothing may ask it before.");
        }
        return built;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** Projected identity params for one DataScopeType row. {@code filter} /
     *  {@code identityFilter} are the raw template values (parsed fresh per call). */
    private record Spec(String identityModel, Object filter, Object identityFilter) {}
}
