package io.softa.starter.user.scope.contributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.Principal;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.enums.ScopeType;
import io.softa.starter.user.scope.PrincipalRefResolver;
import io.softa.starter.user.scope.ScopeContributor;

/**
 * {@link ScopeType#CUSTOM} — admin-authored {@link Filters} JSON. Same
 * tuple-array shape as a {@code FlexQuery.filters}, produced by the FE
 * wizard's {@code FilterDialog}. Letting the framework's
 * {@link Filters#of(String)} deserialize it guarantees operator semantics
 * are identical to runtime user-typed filters.
 *
 * <h3>Dynamic refs ({@code $principal.xxx})</h3>
 * String leaves that match {@code "$principal.<field>"} get substituted
 * with the current principal's value before deserialization.
 *
 * <p>The set of supported {@code <field>} names is the union of:
 * <ul>
 *   <li>{@code userId} — always (read directly from {@link Principal})</li>
 *   <li>Whatever {@link PrincipalRefResolver} beans contribute via
 *       {@link PrincipalRefResolver#refKeys()} — each consuming module
 *       advertises its own ref keys</li>
 * </ul>
 *
 * <h3>Failure semantics</h3>
 * <p>Leaf conditions and AND-composites: an unresolved ref (unknown
 * key, or known key but value missing) invalidates the entire sub-tree.
 * Leaving a {@code "$principal.xxx"} literal would silently match
 * arbitrary string rows, so we drop the whole conjunction.
 *
 * <p>OR-composites: substituted per-disjunct. A
 * disjunct that fails to resolve is dropped; sibling disjuncts continue.
 * This preserves the "at least one of these is my row" fallback pattern
 * — e.g. {@code [["createdId","=","$principal.userId"], "OR",
 * ["assigneeId","=","$principal.employeeId"]]} for a pure user (no
 * employeeId) keeps the {@code createdId = userId} branch alive instead
 * of degrading the whole rule to 0 rows.
 *
 * <p>An OR-composite where <em>every</em> disjunct fails still degrades
 * to empty — safe default.
 */
@Slf4j
@Component
public class CustomScopeContributor implements ScopeContributor {

    private static final String PRINCIPAL_REF_PREFIX = "$principal.";
    private static final String USER_ID_REF = "userId";

    /** Pre-built ref → resolver index. Each ref key MUST map to exactly
     *  one resolver — duplicates throw at construction. */
    private final Map<String, PrincipalRefResolver> resolversByKey;

    public CustomScopeContributor(List<PrincipalRefResolver> resolvers) {
        Map<String, PrincipalRefResolver> index = new HashMap<>();
        for (PrincipalRefResolver r : resolvers) {
            Set<String> keys = r.refKeys();
            if (keys == null) continue;
            for (String key : keys) {
                PrincipalRefResolver prior = index.putIfAbsent(key, r);
                if (prior != null && prior != r) {
                    throw new IllegalStateException(
                            "Multiple PrincipalRefResolver beans claim the same ref key '" + key
                                    + "': " + prior.getClass().getName() + " and "
                                    + r.getClass().getName());
                }
            }
        }
        this.resolversByKey = Map.copyOf(index);
    }

    @Override
    public ScopeType scopeType() {
        return ScopeType.CUSTOM;
    }

    @Override
    public List<String> applicableFields() {
        // Empty on purpose — CUSTOM is universally applicable. The
        // default isApplicableTo check would treat empty as
        // "never applicable", so we override the check below.
        return List.of();
    }

    @Override
    public boolean isApplicableTo(String modelName, java.util.Set<String> modelFieldNames) {
        // CUSTOM lets admins author any Filters expression — the filter
        // itself is validated at compile time. Applicable to every model.
        return true;
    }

    @Override
    public Filters compile(ScopeRule rule, Principal principal, String modelName) {
        JsonNode expr = rule.getScopeExpr();
        if (expr == null || !expr.isArray() || expr.isEmpty()) return new Filters();
        JsonNode substituted = substitutePrincipalRefs(expr, principal);
        if (substituted == null) return new Filters();
        try {
            Filters parsed = Filters.of(substituted.toString());
            return parsed == null ? new Filters() : parsed;
        } catch (Throwable t) {
            log.warn("CustomScope — failed to parse substituted scopeExpr; degrading to empty", t);
            return new Filters();
        }
    }

    private static final String LOGIC_OR = "OR";
    private static final String LOGIC_AND = "AND";

    /** Recursively walk the scopeExpr JSON tree and replace every
     *  {@code "$principal.<field>"} string leaf with the resolved value.
     *  Returns the substituted tree, or {@code null} if the whole tree
     *  degraded (see class-level Failure semantics).
     *
     *  <p>An array containing top-level {@code "OR"} logic tokens is
     *  substituted per-disjunct — failed disjuncts are dropped, surviving
     *  ones stay. All other arrays (leaf tuples like {@code ["field","=",val]},
     *  AND-composites, argument lists) fail-hard on any missing ref. */
    private JsonNode substitutePrincipalRefs(JsonNode node, Principal principal) {
        if (node == null) return null;
        if (node.isString()) {
            String s = node.asString();
            if (!s.startsWith(PRINCIPAL_REF_PREFIX)) return node;
            String field = s.substring(PRINCIPAL_REF_PREFIX.length());
            Object resolved = resolve(field, principal);
            if (resolved == null) return null;
            if (resolved instanceof Number n) {
                return JsonNodeFactory.instance.numberNode(n.longValue());
            }
            return JsonNodeFactory.instance.textNode(resolved.toString());
        }
        if (node.isArray()) {
            // A leaf tuple [field, op, value] is NOT a logic composite — its
            // index-2 VALUE may legitimately equal "OR"/"or" and must not be
            // mistaken for a disjunction separator (which would shred the leaf).
            if (!isLeafTuple(node) && containsTopLevelOr(node)) {
                return substituteOrComposite(node, principal);
            }
            ArrayNode arr = JsonNodeFactory.instance.arrayNode(node.size());
            for (JsonNode child : node) {
                JsonNode sub = substitutePrincipalRefs(child, principal);
                if (sub == null) return null;
                arr.add(sub);
            }
            return arr;
        }
        if (node.isObject()) {
            ObjectNode obj = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                JsonNode sub = substitutePrincipalRefs(e.getValue(), principal);
                if (sub == null) return null;
                obj.set(e.getKey(), sub);
            }
            return obj;
        }
        return node;   // Number / Boolean / Null literals pass through
    }

    /** True iff {@code arr} has a direct {@code "OR"} string child.
     *  {@code "AND"} at the top level is treated as a strict AND-composite
     *  by the caller (any child failure invalidates the whole array). */
    private static boolean containsTopLevelOr(JsonNode arr) {
        for (JsonNode child : arr) {
            if (isOrToken(child)) {
                return true;
            }
        }
        return false;
    }

    /** True iff the node is the DSL OR logic token. Matched case-insensitively
     *  and trimmed to mirror {@code LogicOperator.of} and the downstream
     *  {@code Filters.of} parser — otherwise a legally-spelled {@code "or"} /
     *  {@code " OR "} disjunction would be mistaken for a plain AND child and
     *  silently collapse the whole composite to fail-closed (zero rows) when a
     *  {@code $principal} ref is unresolvable, contradicting the class-level
     *  Failure-semantics contract. */
    private static boolean isOrToken(JsonNode child) {
        return child != null && child.isString() && LOGIC_OR.equalsIgnoreCase(child.asString().trim());
    }

    /** True iff {@code arr} is a query leaf tuple {@code [field, op, value]} —
     *  exactly {@link FilterUnit#UNIT_LENGTH} elements with a recognised query
     *  {@link Operator} at index 1. Mirrors {@code Filters.parseQueryOperator}
     *  so the OR-composite scan never treats a leaf's index-2 VALUE (which may
     *  be the literal string "OR"/"or") as a logic separator. */
    private static boolean isLeafTuple(JsonNode arr) {
        if (arr.size() != FilterUnit.UNIT_LENGTH) return false;
        JsonNode op = arr.get(1);
        if (op == null || !op.isString()) return false;
        try {
            // Operator.of throws the framework's IllegalArgumentException (not
            // java.lang's) for a non-operator string — mirror Filters.parseQueryOperator.
            Operator.of(op.asString().trim());
            return true;
        } catch (io.softa.framework.base.exception.IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * Substitute an OR-composite array: split by {@code "OR"} tokens,
     * substitute each disjunct independently, drop failed disjuncts, and
     * re-assemble surviving ones. Returns null when every disjunct fails
     * (whole rule degrades — safe default).
     *
     * <p>Preserves the top-level array shape when possible: a single
     * surviving disjunct is returned unwrapped (avoids nesting an OR
     * around one child which would confuse the downstream parser).
     */
    private JsonNode substituteOrComposite(JsonNode arr, Principal principal) {
        List<JsonNode> disjuncts = new ArrayList<>();
        List<JsonNode> currentDisjunct = new ArrayList<>();
        for (JsonNode child : arr) {
            if (isOrToken(child)) {
                disjuncts.add(collapseDisjunct(currentDisjunct));
                currentDisjunct = new ArrayList<>();
            } else {
                currentDisjunct.add(child);
            }
        }
        disjuncts.add(collapseDisjunct(currentDisjunct));

        List<JsonNode> substituted = new ArrayList<>();
        for (JsonNode disjunct : disjuncts) {
            JsonNode sub = substitutePrincipalRefs(disjunct, principal);
            if (sub != null) substituted.add(sub);
            else log.debug("CustomScope — dropping OR-disjunct with unresolved $principal ref");
        }
        if (substituted.isEmpty()) return null;
        if (substituted.size() == 1) return substituted.get(0);
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < substituted.size(); i++) {
            if (i > 0) out.add(JsonNodeFactory.instance.textNode(LOGIC_OR));
            out.add(substituted.get(i));
        }
        return out;
    }

    /** Wrap disjunct fragments back into an array, or unwrap a single element.
     *  {@code [a]} becomes {@code a}; multi-element fragments become
     *  {@code [a, b, ...]}. Empty fragment (adjacent OR tokens) returns an
     *  empty ArrayNode — will fail substitution → dropped. */
    private static JsonNode collapseDisjunct(List<JsonNode> fragment) {
        if (fragment.size() == 1) return fragment.get(0);
        ArrayNode arr = JsonNodeFactory.instance.arrayNode(fragment.size());
        for (JsonNode f : fragment) arr.add(f);
        return arr;
    }

    /** Dispatch to the framework-built-in userId path or to a registered
     *  resolver. Returns null on unknown ref or unavailable value. */
    private Object resolve(String field, Principal principal) {
        if (principal == null) return null;
        if (USER_ID_REF.equals(field)) return principal.getUserId();
        PrincipalRefResolver r = resolversByKey.get(field);
        return r == null ? null : r.resolve(field, principal);
    }
}
