package io.softa.starter.permission.scope;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.permission.entity.DataScopeType;
import io.softa.starter.permission.spi.ScopeType;
import static io.softa.starter.permission.scope.ScopeFilterTemplates.fieldRefs;

/**
 * Single source of truth for "which {@link ScopeType}s can apply to a given
 * model".
 *
 * <h3>Data-driven (2026-07-16, filter-merged 2026-07-17)</h3>
 * Reads the {@link DataScopeType} registry (via {@link DataScopeTypeReader}) and
 * matches each row's applicability rule against the queried model's declared
 * fields. This replaces the old per-{@code ScopeContributor} {@code isApplicableTo}
 * iteration: applicability is now DATA, so the role wizard ({@code user-starter})
 * derives the same answer by reading {@code DataScopeType} by name — with no
 * dependency on this engine.
 *
 * <p>For <b>identity types</b> the applicable fields are derived from the row's
 * {@code filter} template (its LHS field references, via
 * {@link ScopeFilterTemplates#fieldRefs}) — the same template
 * {@link IdentityScopeCompiler} compiles, so applicability and compilation can never
 * drift. Code-contributor types (DEPT_SUBTREE / MANAGED_DEPARTMENTS) have no filter
 * and keep their explicit {@code applicableFields}.
 *
 * <h3>Consumed by</h3>
 * <ul>
 *   <li>{@code PermissionServiceImpl} — tells a truly anchorless config model
 *       (only ALL/CUSTOM apply) from real business data that merely has no grant.</li>
 *   <li>{@link ScopeRuleCompiler} — fail-fast on rules whose scope is inapplicable
 *       (e.g. DEPT_SUBTREE on a model with no {@code departmentId}).</li>
 * </ul>
 *
 * <p>Rows are loaded once (lazily, on first use) and cached — {@code DataScopeType}
 * is seed data that only changes on redeploy. A read before the seed lands
 * (fresh DB) returns empty and is retried on the next call rather than cached.
 * {@link ScopeType#ALL} is always included.
 */
@Component
public class ScopeApplicabilityResolver {

    private final DataScopeTypeReader reader;

    /** Lazy cache of projected rules; {@code null} until a non-empty load. */
    private volatile List<Rule> rules;

    public ScopeApplicabilityResolver(DataScopeTypeReader reader) {
        this.reader = reader;
    }

    /**
     * @param modelName e.g. {@code "Employee"} / {@code "LeaveRequest"} — PascalCase.
     * @return the {@link ScopeType}s applicable to this model. {@link ScopeType#ALL}
     *         is always present; every other type is included iff its
     *         {@link DataScopeType} row matches the model's field shape.
     */
    public Set<ScopeType> applicableFor(String modelName) {
        EnumSet<ScopeType> out = EnumSet.of(ScopeType.ALL);
        if (modelName == null || !ModelManager.existModel(modelName)) {
            return out;
        }
        Set<String> fieldNames = new HashSet<>();
        for (MetaField mf : ModelManager.getModelFields(modelName)) {
            fieldNames.add(mf.getFieldName());
        }
        for (Rule r : rules()) {
            if (r.matches(modelName, fieldNames)) {
                ScopeType t = parse(r.code());
                if (t != null) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** Lazy-load the registry. Caches only a non-empty result so a read before
     *  the seed lands (fresh DB) is retried rather than pinned to empty. */
    private List<Rule> rules() {
        List<Rule> cached = rules;
        if (cached != null) {
            return cached;
        }
        List<Map<String, Object>> raw = reader.read();
        if (raw.isEmpty()) {
            return List.of();
        }
        List<Rule> loaded = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            loaded.add(Rule.of(m));
        }
        List<Rule> immutable = List.copyOf(loaded);
        rules = immutable;
        return immutable;
    }

    private static ScopeType parse(String code) {
        if (code == null) {
            return null;
        }
        try {
            return ScopeType.valueOf(code);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * One applicability rule, projected from a {@link DataScopeType} row.
     *
     * <p>Identity types carry a {@code filter} template, so their applicable fields
     * are <b>derived from the template's field references</b> ({@code filterFields},
     * plus {@code identityFilterFields} for the {@code identityModel} model-swap) —
     * no {@code applicableFields} on these rows. Code-contributor types
     * (DEPT_SUBTREE / MANAGED_DEPARTMENTS) have no filter and fall back to their
     * explicit {@code applicableFields}.
     */
    private record Rule(String code, boolean appliesToAll, boolean identity,
                        List<String> applicableFields, String identityModel,
                        Set<String> filterFields, Set<String> identityFilterFields) {

        boolean matches(String model, Set<String> fields) {
            if (appliesToAll) {
                return true;
            }
            if (identity) {
                if (identityModel != null && identityModel.equals(model)) {
                    return !identityFilterFields.isEmpty() && fields.containsAll(identityFilterFields);
                }
                return !filterFields.isEmpty() && fields.containsAll(filterFields);
            }
            for (String f : applicableFields) {
                if (fields.contains(f)) {
                    return true;
                }
            }
            return false;
        }

        static Rule of(Map<String, Object> m) {
            Object all = m.get("appliesToAll");
            boolean appliesToAll = all instanceof Boolean b ? b
                    : Boolean.parseBoolean(String.valueOf(all));
            Object filter = m.get("filter");
            boolean identity = filter != null;
            List<String> applicableFields = identity ? List.of()
                    : JsonUtils.toStringList(m.get("applicableFields"));
            return new Rule(str(m.get("id")), appliesToAll, identity,
                    applicableFields == null ? List.of() : applicableFields,
                    str(m.get("identityModel")),
                    identity ? fieldRefs(filter) : Set.of(),
                    identity ? fieldRefs(m.get("identityFilter")) : Set.of());
        }

        private static String str(Object v) {
            return v == null ? null : v.toString();
        }
    }
}
