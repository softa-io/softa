package io.softa.framework.orm.jdbc.database.dialect;

import io.softa.framework.base.enums.Operator;

public interface DialectInterface {

    /**
     * Get the predicate of the database query operator: >, =, IN, etc.
     *
     * @param operator FilterUnit operator
     * @return SQL operator predicate string
     */
    String getPredicate(Operator operator);

    /**
     * Whether the database supports row-value tuple predicates such as `(c1, c2) IN ((?, ?), (?, ?))`.
     *
     * @return whether tuple predicates are supported
     */
    default boolean supportsTuplePredicate() {
        return true;
    }

    /**
     * Get the database paging clause.
     *
     * @param limit limit
     * @param offset offset
     * @return paging clause
     */
    StringBuilder getPageClause(int limit, int offset);
}
