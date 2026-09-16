package io.softa.starter.permission.scope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.enums.FilterType;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns {@code <deptField> CHILD OF <deptIds>} into a filter that actually means
 * "in that department or under it".
 *
 * <h3>Why a rewrite, and not just letting the operator through</h3>
 *
 * {@link Operator#CHILD_OF} compiles to {@code LIKE '<value>%'} on the field it names. Pointed at a
 * ToOne column that holds a department <b>id</b>, that reads the id as though it were a path —
 * {@code department_id LIKE '873%'} — which matches by numeric coincidence and nothing else. The
 * tree lives in {@code Department.idPath}, so the condition has to move onto that column, and the
 * ids have to become paths first.
 *
 * <p>Neither step is something a client can do. The browser does not know what {@code idPath} looks
 * like, that a separator has to be appended, or which column on this model leads to Department. So
 * the UI sends the operator on the field the user actually picked, and this class is where it
 * becomes correct SQL.
 *
 * <h3>The separator is not cosmetic</h3>
 *
 * A path segment has no trailing separator, so {@code LIKE '1/12%'} also matches {@code 1/120} —
 * a different department that merely starts with the same digits. Every subtree filter is therefore
 * two branches:
 *
 * <pre>
 *   field.idPath =        '1/12'      ← the root itself
 *   OR field.idPath LIKE  '1/12/%'    ← its descendants, separator forced
 * </pre>
 *
 * <p>The root <b>is</b> included, matching {@code DEPT_SUBTREE}. Were the two to differ, the same
 * department would select different rows in a role's data scope than in the filter bar.
 *
 * <h3>Which field, exactly</h3>
 *
 * The rewrite stays on the field the caller named and appends {@code .idPath} to it. It deliberately
 * does NOT consult {@link DepartmentCascadePathResolver}: that answers "how does this model reach
 * Department", one answer per model, which is the right question for a scope rule that names no
 * field. Here the caller named one — and a model can have several. On Employee, filtering
 * {@code additionalDepartmentId} would otherwise be silently rewritten onto {@code departmentId} and
 * return a different set of employees than the one asked for.
 *
 * <h3>Fail-closed</h3>
 *
 * An id that resolves to no path — unknown, soft-deleted, another tenant's — contributes no branch.
 * If that leaves the condition with none at all, it becomes {@link ScopeRuleCompiler#matchNone()}
 * rather than disappearing: dropping a condition widens the result, and a filter the user asked for
 * must never come back with MORE rows than it named.
 */
@Slf4j
@Component
public class DepartmentSubtreeFilterRewriter {

    private static final String DEPARTMENT_MODEL = "Department";
    private static final String ID_PATH_SUFFIX = ".idPath";
    private static final String PATH_SEPARATOR = "/";

    private final DepartmentIdPathResolver idPathResolver;

    public DepartmentSubtreeFilterRewriter(DepartmentIdPathResolver idPathResolver) {
        this.idPathResolver = idPathResolver;
    }

    /**
     * @param modelName the model being queried, used to resolve the named field's metadata
     * @param filters the caller's filters, returned untouched when they carry no department subtree
     *     condition — which is almost every query
     * @return filters with each department {@code CHILD_OF} expanded onto {@code idPath}
     */
    public Filters rewrite(String modelName, Filters filters) {
        if (modelName == null || filters == null || Filters.isEmpty(filters)) {
            return filters;
        }
        // Cheap scan first: rebuilding the tree costs allocations on every read, and the operator
        // appears in a vanishing fraction of them.
        if (!containsChildOf(filters)) {
            return filters;
        }
        return rewriteNode(modelName, filters);
    }

    private static boolean containsChildOf(Filters node) {
        if (node == null) {
            return false;
        }
        if (node.getType() == FilterType.LEAF) {
            FilterUnit unit = node.getFilterUnit();
            return unit != null && Operator.CHILD_OF.equals(unit.getOperator());
        }
        if (node.getType() == FilterType.TREE && node.getChildren() != null) {
            return node.getChildren().stream().anyMatch(DepartmentSubtreeFilterRewriter::containsChildOf);
        }
        return false;
    }

    private Filters rewriteNode(String modelName, Filters node) {
        if (node.getType() == FilterType.LEAF) {
            return rewriteLeaf(modelName, node);
        }
        if (node.getType() != FilterType.TREE || node.getChildren() == null) {
            return node;
        }
        List<Filters> rewritten = new ArrayList<>(node.getChildren().size());
        for (Filters child : node.getChildren()) {
            rewritten.add(rewriteNode(modelName, child));
        }
        // Rebuilt rather than mutated: the caller's Filters may be a scope rule's compiled output,
        // which is cached and shared across requests.
        Filters copy = new Filters();
        copy.setType(FilterType.TREE);
        copy.setLogicOperator(node.getLogicOperator());
        copy.setChildren(rewritten);
        return copy;
    }

    private Filters rewriteLeaf(String modelName, Filters leaf) {
        FilterUnit unit = leaf.getFilterUnit();
        if (unit == null || !Operator.CHILD_OF.equals(unit.getOperator())) {
            return leaf;
        }
        String field = unit.getField();
        if (field == null || !pointsAtDepartment(modelName, field)) {
            // CHILD_OF on something that is not a department reference — an idPath column being
            // filtered directly, most likely. Already correct; leave it alone.
            return leaf;
        }

        List<Long> deptIds = departmentIds(unit.getValue());
        if (deptIds.isEmpty()) {
            return ScopeRuleCompiler.matchNone();
        }

        String pathField = field + ID_PATH_SUFFIX;
        List<Filters> branches = new ArrayList<>();
        for (String rootPath : idPathResolver.idPathsOf(deptIds)) {
            if (rootPath == null || rootPath.isEmpty()) {
                continue;
            }
            branches.add(Filters.or(
                    Filters.of(pathField, Operator.EQUAL, rootPath),
                    new Filters().childOf(pathField, rootPath + PATH_SEPARATOR)));
        }
        if (branches.isEmpty()) {
            // Every id was unknown / soft-deleted / another tenant's. Match nothing rather than
            // dropping the condition, which would hand back rows the caller never asked for.
            log.debug("Department subtree filter on {}.{} resolved no id paths; matching no rows",
                    modelName, field);
            return ScopeRuleCompiler.matchNone();
        }
        return branches.size() == 1 ? branches.get(0) : orAll(branches);
    }

    /** True when the named field is a ToOne holding a Department id. */
    private static boolean pointsAtDepartment(String modelName, String field) {
        MetaField metaField = resolveField(modelName, field);
        if (metaField == null) {
            return false;
        }
        FieldType fieldType = metaField.getFieldType();
        if (fieldType != FieldType.MANY_TO_ONE && fieldType != FieldType.ONE_TO_ONE) {
            return false;
        }
        return DEPARTMENT_MODEL.equals(metaField.getRelatedModel());
    }

    /**
     * The metadata for a field that may be a cascaded path ({@code employeeId.departmentId}).
     * Unknown fields resolve to null rather than throwing: a filter naming a field this model does
     * not have is the query layer's error to report, not this rewriter's.
     */
    private static MetaField resolveField(String modelName, String field) {
        try {
            return field.contains(".")
                    ? ModelManager.getLastFieldOfCascaded(modelName, field)
                    : ModelManager.getModelFieldOrNull(modelName, field);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The operator is collection-valued, but a single id round-trips as a scalar. */
    private static List<Long> departmentIds(Object value) {
        Set<Long> ids = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                toDeptId(item).ifPresent(ids::add);
            }
        } else {
            toDeptId(value).ifPresent(ids::add);
        }
        return new ArrayList<>(ids);
    }

    /** Ids arrive as Long from Java callers and as String from JSON; anything else is not an id. */
    private static Optional<Long> toDeptId(Object raw) {
        if (raw instanceof Number number) {
            return Optional.of(number.longValue());
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Optional.of(Long.valueOf(text.trim()));
            } catch (NumberFormatException ex) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Filters orAll(List<Filters> branches) {
        Filters combined = branches.get(0);
        for (int i = 1; i < branches.size(); i++) {
            combined = Filters.or(combined, branches.get(i));
        }
        return combined;
    }
}
