package io.softa.framework.orm.meta;

/**
 * What a field path ({@code deptId.code}) is used for. Each use crosses relations by rules of its own —
 * the rules the query builders and the cascaded read apply — and {@link FieldPaths} states them in one
 * place, so a check made before a path is stored (a template's filters, an export column) answers what
 * the run will.
 */
public enum FieldPathUse {

    /**
     * A condition in a WHERE ({@code Filters}). Crosses a to-one relation by a join and a to-many one by
     * a sub-query; ends on a stored field, on a to-many relation (its related ids are compared), or — when
     * the field is the first of its query, the path's only segment or the one right after a to-many hop —
     * on a dynamic cascaded field, which the builder rewrites to its path.
     */
    FILTER,

    /**
     * An ORDER BY ({@code Orders}). Crosses to-one relations only, and ends on a stored field that is not
     * a to-many relation, or on a dynamic cascaded field of the model's own.
     */
    SORT,

    /**
     * A value read through relations — a list column or an export column such as {@code deptId.name}.
     * Crosses to-one relations only; the field it ends on is read as the related record's own read
     * answers it.
     */
    READ
}
