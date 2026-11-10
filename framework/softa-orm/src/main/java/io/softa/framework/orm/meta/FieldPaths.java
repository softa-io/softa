package io.softa.framework.orm.meta;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.FilterType;

/**
 * Whether a field path can serve a {@link FieldPathUse} on a model — the rules the WHERE builder, the ORDER BY
 * builder and the cascaded read apply, stated once, for a check made before the path is stored.
 *
 * <p>A template's filters, orders and columns are strings until something runs them. Checked only then, a
 * wrong path fails on whoever runs the template, long after it was saved; checked here, with the rules the
 * run applies, a template that saves does not fail on a path. The traversal is {@link CascadeFieldWalker}'s;
 * what each use adds is where the path may cross a to-many relation and what it may end on:
 * <ul>
 *   <li>{@link FieldPathUse#FILTER} — {@code WhereBuilder.parseFilterUnit} and {@code parseCascadeField}, and
 *       {@code resolveTupleField} for a condition on several fields at once;</li>
 *   <li>{@link FieldPathUse#SORT} — {@code BaseBuilder.parseLogicField} and {@code parseStoredField};</li>
 *   <li>{@link FieldPathUse#READ} — {@link ModelManager#getLastFieldOfCascaded}, which the cascaded read
 *       ({@code MetaField.createDynamicField}) and the export's column headers resolve through.</li>
 * </ul>
 * Messages say what is wrong, not where the path is used; the caller names the field the path came from.
 */
public final class FieldPaths {

    private FieldPaths() {
    }

    /**
     * Why {@code path} cannot serve {@code use} on {@code modelName}, or null when it can.
     *
     * @param modelName the model the path starts on
     * @param path      a field name, or a dotted path through relations
     * @param use       what the path is for
     * @return the reason, or null
     */
    public static @Nullable String problem(String modelName, String path, FieldPathUse use) {
        if (StringUtils.isBlank(path)) {
            return "The field is empty.";
        }
        if (StringUtils.isBlank(modelName) || !ModelManager.existModel(modelName)) {
            return "Model '" + modelName + "' does not exist.";
        }
        if (FieldPathUse.FILTER.equals(use) && ModelConstant.SEARCH_NAME.equals(path)) {
            // The name search, which the WHERE builder answers itself; not a field of the model.
            return null;
        }
        return switch (use) {
            case FILTER -> check(modelName, path, true, Leaf.CONDITION);
            case SORT -> check(modelName, path, false, Leaf.ORDER);
            case READ -> check(modelName, path, false, Leaf.ANY);
        };
    }

    /**
     * Every field a condition names that cannot stand in a WHERE on {@code modelName}, each as
     * {@code 'path': reason}; empty when there is no condition or nothing is wrong with it.
     */
    public static List<String> filterProblems(String modelName, @Nullable Filters filters) {
        List<String> problems = new ArrayList<>();
        if (!Filters.isEmpty(filters)) {
            collectFilterProblems(modelName, filters, problems);
        }
        return problems;
    }

    /**
     * Every field an order names that cannot stand in an ORDER BY on {@code modelName}, each as
     * {@code 'path': reason}; empty when there is no order or nothing is wrong with it.
     */
    public static List<String> orderProblems(String modelName, @Nullable Orders orders) {
        List<String> problems = new ArrayList<>();
        if (orders == null || orders.isEmpty()) {
            return problems;
        }
        for (List<String> order : orders.getOrderList()) {
            String path = order.isEmpty() ? null : order.getFirst();
            String problem = problem(modelName, path, FieldPathUse.SORT);
            if (problem != null) {
                problems.add(named(path, problem));
            }
        }
        return problems;
    }

    /** The path and what is wrong with it, as the collection checks report each. */
    public static String named(@Nullable String path, String problem) {
        return "'" + StringUtils.defaultString(path) + "': " + problem;
    }

    private static void collectFilterProblems(String modelName, Filters filters, List<String> problems) {
        if (FilterType.TREE.equals(filters.getType())) {
            filters.getChildren().forEach(child -> collectFilterProblems(modelName, child, problems));
            return;
        }
        if (!FilterType.LEAF.equals(filters.getType()) || filters.getFilterUnit() == null) {
            return;
        }
        FilterUnit unit = filters.getFilterUnit();
        for (String path : unit.getEffectiveFields()) {
            String problem = unit.isTuple() ? tupleProblem(modelName, path) : problem(modelName, path, FieldPathUse.FILTER);
            if (problem != null) {
                problems.add(named(path, problem));
            }
        }
    }

    /**
     * A field of a condition on several fields at once ({@code [["a", "b"], "IN", [[1, 2]]]}): the tuple is
     * compared column by column, so every field is joined to — no sub-query — and ends on a column.
     */
    private static @Nullable String tupleProblem(String modelName, String path) {
        if (StringUtils.isBlank(path)) {
            return "The field is empty.";
        }
        if (!ModelManager.existModel(modelName)) {
            return "Model '" + modelName + "' does not exist.";
        }
        if (ModelConstant.SEARCH_NAME.equals(path)) {
            return "A condition on several fields cannot use the name search.";
        }
        return check(modelName, path, false, Leaf.TUPLE);
    }

    /** What the field a path ends on may be. */
    private enum Leaf {
        /** A WHERE: a column, a to-many relation, or a cascaded field first in its query. */
        CONDITION,
        /** An ORDER BY: a column — never a to-many relation — or a cascaded field of the model's own. */
        ORDER,
        /** A tuple condition's field: as an order's. */
        TUPLE,
        /** A read: whatever the related record's read answers. */
        ANY
    }

    private static @Nullable String check(String modelName, String path, boolean throughToMany, Leaf leafRule) {
        // Where the path last crossed a to-many relation: the field right after one starts a sub-query of
        // its own, and a cascaded field is rewritten only there, as at the start of the path.
        int[] lastToMany = {-1};
        CascadeFieldWalker.Visitor visitor = new CascadeFieldWalker.Visitor() {
            @Override
            public void onAdvance(int index, MetaField field, String nextModel) {
                if (FieldType.TO_MANY_TYPES.contains(field.getFieldType())) {
                    lastToMany[0] = index;
                }
            }
        };
        CascadeFieldWalker.Result result;
        try {
            result = CascadeFieldWalker.walk(modelName, path, BaseConstant.CASCADE_LEVEL, throughToMany, visitor);
        } catch (RuntimeException e) {
            // A relation onto a model the registry does not hold.
            return e.getMessage();
        }
        if (result instanceof CascadeFieldWalker.Result.Failure failure) {
            return failure.message() + ".";
        }
        MetaField leaf = ((CascadeFieldWalker.Result.Ok) result).leaf();
        boolean firstOfItsQuery = CascadeFieldWalker.segmentCount(path) - 1 == lastToMany[0] + 1;
        return leafProblem(leaf, leafRule, firstOfItsQuery);
    }

    private static @Nullable String leafProblem(MetaField leaf, Leaf rule, boolean firstOfItsQuery) {
        boolean toMany = FieldType.TO_MANY_TYPES.contains(leaf.getFieldType());
        // A cascaded field is a column of another model, rewritten to its path where the builder meets it
        // first; anything else dynamic has no column at all.
        boolean noColumn = leaf.isDynamic() && !(firstOfItsQuery && leaf.isDynamicCascadedField());
        return switch (rule) {
            case ANY -> null;
            case CONDITION -> !toMany && noColumn
                    ? "'" + leaf.getFieldName() + "' is not stored, so a condition cannot test it."
                    : null;
            case ORDER -> toMany
                    ? "Cannot sort by '" + leaf.getFieldName() + "' (" + leaf.getFieldType().getType() + ")."
                    : noColumn ? "'" + leaf.getFieldName() + "' is not stored, so there is no column to sort by." : null;
            case TUPLE -> toMany
                    ? "A condition on several fields cannot compare '" + leaf.getFieldName() + "' ("
                    + leaf.getFieldType().getType() + ")."
                    : noColumn ? "'" + leaf.getFieldName() + "' is not stored, so a condition cannot test it." : null;
        };
    }
}
