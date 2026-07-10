package io.softa.starter.user.scope;

import java.util.List;
import java.util.Set;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.enums.ScopeType;

/**
 * SPI — plug-in implementation of a single {@link ScopeType}'s row-filter
 * compilation logic. {@link ScopeRuleCompiler} dispatches each rule to the
 * contributor whose {@link #scopeType()} matches the rule's type.
 *
 * <h3>Why a registry, not a switch</h3>
 * The framework's three generic scope types (ALL, CUSTOM, CREATED_BY_SELF)
 * are domain-agnostic — they live in user-starter. Domain-specific types
 * (SELF, DIRECT_REPORTS, DEPT_SUBTREE, MANAGED_DEPARTMENTS, LEGAL_ENTITY)
 * carry semantics that depend on the consuming app's business shape — they
 * live in the consuming business module. Dispatching through this registry
 * lets the same compiler handle both, without user-starter importing
 * business concepts.
 *
 * <h3>How to register</h3>
 * Mark your implementation as {@code @Component}. Spring collects all
 * beans of this type and injects them into {@link ScopeRuleCompiler}. At
 * startup every {@link ScopeType} value should have exactly one
 * contributor — duplicates are rejected, missing contributors log a
 * warning (and any rule referencing the orphaned type degrades to
 * fail-closed at compile time).
 */
public interface ScopeContributor {

    /**
     * The single {@link ScopeType} this contributor implements. Used as
     * the dispatch key by {@link ScopeRuleCompiler}.
     */
    ScopeType scopeType();

    /**
     * Anchor field names this scope type uses on the queried model.
     * Feeds the default {@link #isApplicableTo} check — override that
     * method directly when applicability doesn't reduce to a flat
     * field-name lookup.
     *
     * <p>Return {@link List#of()} for universally-applicable scopes
     * (in which case override {@link #isApplicableTo} to return
     * {@code true}) or when applicability is decided entirely by an
     * override.
     *
     * <p>Multiple fields = OR semantics (any present field enables).
     */
    List<String> applicableFields();

    /**
     * Whether this contributor's scope can be applied to {@code modelName}.
     * Decided by {@link ScopeApplicabilityResolver} at rule-compile time
     * and at Wizard-render time — a scope reported as inapplicable is
     * fail-closed in the compiler and hidden in the FE checkbox grid.
     *
     * <h3>Default check</h3>
     * The default implementation is a flat OR over {@link #applicableFields()}
     * against the model's direct field names — sufficient for the simple
     * cases (SELF wants {@code employeeId}, LEGAL_ENTITY wants
     * {@code legalEntityId}, etc.).
     *
     * <h3>When to override</h3>
     * <ul>
     *   <li><b>Universal applicability</b>: {@code CustomScopeContributor}
     *       and {@code CreatedBySelfScopeContributor} return {@code true}
     *       unconditionally.</li>
     *   <li><b>Cascade-path resolution</b>: a HR-domain contributor
     *       whose scope reaches the anchor field indirectly
     *       (e.g. {@code LeaveRequest → employeeId.departmentId} for
     *       DEPT_SUBTREE) must return {@code true} even though the
     *       model has no direct {@code departmentId} field. Consult
     *       the domain-specific cascade resolver in the override.</li>
     *   <li><b>Model-identity special cases</b>: SELF on the
     *       {@code Employee} model itself matches on {@code id}, not
     *       {@code employeeId} — that knowledge belongs in the
     *       contributor (which owns the domain), not in the framework
     *       resolver.</li>
     * </ul>
     *
     * @param modelName        queried model's {@code MetaModel.modelName}
     * @param modelFieldNames  direct field names on the model (from
     *                         {@code ModelManager.getModelFields}) — passed
     *                         in as a Set for O(1) contains lookups.
     */
    default boolean isApplicableTo(String modelName, Set<String> modelFieldNames) {
        List<String> fields = applicableFields();
        if (fields == null || fields.isEmpty()) return false;
        for (String field : fields) {
            if (modelFieldNames.contains(field)) return true;
        }
        return false;
    }

    /**
     * Compile this rule into a {@link Filters} for the queried model.
     * Return {@link Filters#EMPTY} (i.e. {@code new Filters()}) to
     * fail-closed for this rule — the caller still OR-merges with other
     * rules, but this one contributes "no rows" rather than "every row".
     *
     * <p>Contributors that need the caller's identity read it straight from
     * {@code ContextHolder.getContext()} — {@code getUserId()} for the user
     * id, {@code getEmpInfo()} for the per-request HR context (populated by
     * the app's {@code ContextEnricher}). The compiler no longer threads a
     * {@code Principal} through; env placeholders inside a CUSTOM filter are
     * resolved later by {@code FilterUnitParser} at SQL-build time.
     *
     * <p>The {@code modelName} is the queried model in PascalCase.
     * Contributors use this to pick the right anchor column when a model
     * deviates from the default convention.
     */
    Filters compile(ScopeRule rule, String modelName);
}
