package io.softa.framework.orm.jdbc.database;

import java.util.ArrayList;
import java.util.List;

import io.softa.framework.orm.jdbc.database.builder.SqlClauseBuilder;

/**
 * SQL Builder Chain
 */
public class SqlBuilderChain {
    private final List<SqlClauseBuilder> builders = new ArrayList<>();

    /**
     * Add builder
     * @param builder builder
     * @return this
     */
    public SqlBuilderChain addBuilder(SqlClauseBuilder builder) {
        builders.add(builder);
        return this;
    }

    /**
     * Execute the responsibility chain builder to build the SQL statement.
     */
    public void build() {
        builders.forEach(SqlClauseBuilder::build);
    }
}
