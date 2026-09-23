package io.softa.starter.permission.scope;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.enums.FilterType;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns {@code <relationField> CHILD OF <ids>} into a filter that actually means
 * "that row or anything under it".
 *
 * <h3>Why a rewrite, and not just letting the operator through</h3>
 *
 * {@link Operator#CHILD_OF} compiles to {@code LIKE '<value>%'} on the field it names. Pointed at a
 * ToOne column that holds an <b>id</b>, that reads the id as though it were a path —
 * {@code department_id LIKE '873%'} — which matches by numeric coincidence and nothing else. The
 * tree lives in the related model's {@code idPath}, so the condition has to move onto that column,
 * and the ids have to become paths first.
 *
 * <p>Neither step is something a client can do. The browser does not know what {@code idPath} looks
 * like, that a separator has to be appended, or that the tree lives on the related model at all. So
 * the UI sends the operator on the field the user actually picked, and this class is where it
 * becomes correct SQL.
 *
 * <h3>The separator is not cosmetic</h3>
 *
 * A path segment has no trailing separator, so {@code LIKE '1/12%'} also matches {@code 1/120} —
 * a different node that merely starts with the same digits. Every subtree filter is therefore
 * two branches:
 *
 * <pre>
 *   field.idPath =        '1/12'      ← the root itself
 *   OR field.idPath LIKE  '1/12/%'    ← its descendants, separator forced
 * </pre>
 *
 * <p>The root <b>is</b> included, matching {@code DEPT_SUBTREE}. Were the two to differ, the same
 * node would select different rows in a role's data scope than in the filter bar.

 *
 * <h3>Which relations qualify</h3>
 *
 * Any ToOne whose related model both points at itself and stores the resulting {@code idPath}. The
 * two together are what make a prefix match mean "descendant of": a self-reference without a stored
 * path would need a recursive walk instead, and a stray {@code idPath} on a flat model is a column
 * name, not a tree. Nothing here names a particular model — which is deliberate, because a client
 * offering this operator cannot ask a model whether it is a tree, and would otherwise be reduced to
 * naming one.
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
public class SubtreeFilterRewriter {

    private static final String ID_FIELD = "id";

    private final ModelService<Serializable> modelService;

    public SubtreeFilterRewriter(ModelService<Serializable> modelService) {
        this.modelService = modelService;
    }

    /**
     * @param modelName the model being queried, used to resolve the named field's metadata
     * @param filters the caller's filters, returned untouched when they carry no subtree condition
     *     — which is almost every query
     * @return filters with each {@code CHILD_OF} on a tree reference expanded onto that tree's
     *     {@code idPath}
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
            return node.getChildren().stream().anyMatch(SubtreeFilterRewriter::containsChildOf);
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
        String treeModel = field == null ? null : treeModelOf(modelName, field);
        if (treeModel == null) {
            // CHILD_OF on something that is not a reference to a tree — an idPath column being
            // filtered directly, most likely. Already correct; leave it alone.
            return leaf;
        }

        List<Long> rootIds = rootIds(unit.getValue());
        if (rootIds.isEmpty()) {
            return ScopeRuleCompiler.matchNone();
        }

        String pathField = IdPath.fieldOn(field);
        List<Filters> branches = new ArrayList<>();
        for (String rootPath : idPathsOf(treeModel, rootIds)) {
            if (rootPath == null || rootPath.isEmpty()) {
                continue;
            }
            branches.add(IdPath.subtreeOf(pathField, rootPath));
        }
        if (branches.isEmpty()) {
            // Every id was unknown / soft-deleted / another tenant's. Match nothing rather than
            // dropping the condition, which would hand back rows the caller never asked for.
            log.debug("Subtree filter on {}.{} resolved no id paths; matching no rows",
                    modelName, field);
            return ScopeRuleCompiler.matchNone();
        }
        return branches.size() == 1 ? branches.get(0) : orAll(branches);
    }

    /** The tree the named field points at, or null when it points at something else. */
    private static String treeModelOf(String modelName, String field) {
        MetaField metaField = resolveField(modelName, field);
        if (metaField == null) {
            return null;
        }
        FieldType fieldType = metaField.getFieldType();
        if (fieldType != FieldType.MANY_TO_ONE && fieldType != FieldType.ONE_TO_ONE) {
            return null;
        }
        String related = metaField.getRelatedModel();
        return isPathTree(related) ? related : null;
    }

    /**
     * A model is a tree this operator can address when it both points at itself and stores the
     * resulting path. Either alone is not enough: a self-reference without a stored path would need
     * a recursive walk rather than a prefix match, and an {@code idPath} on a model that references
     * no parent is a column that happens to share the name.
     */
    private static boolean isPathTree(String modelName) {
        if (modelName == null || !ModelManager.existModel(modelName)) {
            return false;
        }
        MetaField path = ModelManager.getModelFieldOrNull(modelName, IdPath.FIELD);
        if (path == null || path.getFieldType() != FieldType.STRING) {
            return false;
        }
        return ModelManager.getModelFields(modelName).stream().anyMatch(f ->
                (f.getFieldType() == FieldType.MANY_TO_ONE || f.getFieldType() == FieldType.ONE_TO_ONE)
                        && modelName.equals(f.getRelatedModel()));
    }

    /**
     * The roots' paths.
     *
     * <p>Read straight rather than through a cache. The cached tree that {@code DEPT_SUBTREE} uses
     * holds one model and loads all of it, which earns its keep on a scope evaluated every request;
     * this reads the handful of rows the caller named, on the rare query that names any. Permission
     * checks are waived for the same reason they are on any id-to-display-value lookup: the caller
     * already named these ids, and the rows the filter goes on to select answer to their own scope.
     * Re-entry is not a concern — the query issued here carries no {@code CHILD_OF}, so the scan at
     * the top of {@link #rewrite} returns immediately.
     */
    private List<String> idPathsOf(String treeModel, List<Long> rootIds) {
        Context isolated = ContextHolder.getContext().copy();
        isolated.setSkipPermissionCheck(true);
        List<Map<String, Object>> rows = ContextHolder.callWith(isolated,
                () -> modelService.searchList(treeModel, new FlexQuery(List.of(IdPath.FIELD),
                        Filters.of(ID_FIELD, Operator.IN, rootIds))));
        List<String> paths = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (row.get(IdPath.FIELD) instanceof CharSequence path && !path.isEmpty()) {
                paths.add(path.toString());
            }
        }
        return paths;
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
    private static List<Long> rootIds(Object value) {
        Set<Long> ids = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                toRootId(item).ifPresent(ids::add);
            }
        } else {
            toRootId(value).ifPresent(ids::add);
        }
        return new ArrayList<>(ids);
    }

    /** Ids arrive as Long from Java callers and as String from JSON; anything else is not an id. */
    private static Optional<Long> toRootId(Object raw) {
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
