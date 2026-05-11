package io.softa.starter.metadata.sequence.service.dialect;

import io.softa.framework.orm.jdbc.JdbcProxy;
import io.softa.framework.orm.jdbc.database.SqlParams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MySQL sequence dialect that fuses the read into the UPDATE via
 * {@code LAST_INSERT_ID(expr)}: the same statement advances
 * {@code current_value} and stashes it in the session register, and a
 * subsequent {@code SELECT LAST_INSERT_ID()} reads it back on the same
 * connection without a second row read.
 *
 * <p>The {@code tenant_id} predicate is redundant given {@code id} is
 * unique, but it provides a defensive guard against cross-tenant writes
 * (see {@link StandardSequenceDialect} for the same rationale). NULL-safe
 * equality uses MySQL's {@code <=>} operator (vs. the portable
 * {@code OR ... IS NULL} pattern used by {@link StandardSequenceDialect}).
 */
@Component
@RequiredArgsConstructor
public class MySqlSequenceDialect implements SequenceDialect {

    private static final String SQL_SINGLE = """
            UPDATE sys_sequence
            SET current_value = LAST_INSERT_ID(
                    CASE
                        WHEN last_reset_key <=> ? THEN current_value + ?
                        ELSE ?
                    END
                ),
                last_reset_key = ?,
                updated_time = CURRENT_TIMESTAMP
            WHERE id = ?
              AND tenant_id <=> ?
            """;

    private static final String SQL_BATCH = """
            UPDATE sys_sequence
            SET current_value = LAST_INSERT_ID(
                    CASE
                        WHEN last_reset_key <=> ? THEN current_value + ? * ?
                        ELSE ? + (? - 1) * ?
                    END
                ),
                last_reset_key = ?,
                updated_time = CURRENT_TIMESTAMP
            WHERE id = ?
              AND tenant_id <=> ?
            """;

    private static final String SQL_LAST_INSERT_ID = "SELECT LAST_INSERT_ID()";

    private final JdbcProxy jdbcProxy;

    @Override
    public SqlParams buildAllocateSql(String currentKey, long step, long startValue, int count, Long id, Long tenantId) {
        SqlParams sqlParams;
        if (count == 1) {
            // SQL_SINGLE placeholders: currentKey, step, startValue, currentKey, id, tenantId
            sqlParams = new SqlParams(SQL_SINGLE);
            sqlParams.addArgValue(currentKey);
            sqlParams.addArgValue(step);
            sqlParams.addArgValue(startValue);
            sqlParams.addArgValue(currentKey);
            sqlParams.addArgValue(id);
            sqlParams.addArgValue(tenantId);
        } else {
            // SQL_BATCH placeholders: currentKey, step, count, startValue, count, step, currentKey, id, tenantId
            sqlParams = new SqlParams(SQL_BATCH);
            sqlParams.addArgValue(currentKey);
            sqlParams.addArgValue(step);
            sqlParams.addArgValue(count);
            sqlParams.addArgValue(startValue);
            sqlParams.addArgValue(count);
            sqlParams.addArgValue(step);
            sqlParams.addArgValue(currentKey);
            sqlParams.addArgValue(id);
            sqlParams.addArgValue(tenantId);
        }
        return sqlParams;
    }

    @Override
    public Long fetchEndValue(String modelName, Long id, Long tenantId) {
        return (Long) jdbcProxy.queryForObject(modelName, new SqlParams(SQL_LAST_INSERT_ID), Long.class);
    }
}
