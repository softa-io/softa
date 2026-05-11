package io.softa.starter.metadata.sequence.service.dialect;

import io.softa.framework.orm.jdbc.JdbcProxy;
import io.softa.framework.orm.jdbc.database.SqlParams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Portable sequence allocation for PostgreSQL/Oracle/SQL Server.
 *
 * <p>Pattern: a single {@code UPDATE} advances {@code current_value}, then a
 * follow-up {@code SELECT} reads it back. The read sees the writer's own
 * update because both run inside the same transaction; the exclusive row lock
 * acquired by the UPDATE blocks concurrent allocations until commit.
 * MySQL has its own {@link MySqlSequenceDialect} that fuses the read into the
 * UPDATE via {@code LAST_INSERT_ID(expr)}.
 *
 * <p>The {@code tenant_id} predicate in the WHERE clauses is intentionally
 * redundant: the row is already uniquely identified by {@code id}, but the
 * extra check provides a defensive guard against cross-tenant writes if a
 * stale cached {@link io.softa.starter.metadata.sequence.entity.SysSequence}
 * id ever leaks across tenants.
 */
@Component
@RequiredArgsConstructor
public class StandardSequenceDialect implements SequenceDialect {

    private static final String SQL_SINGLE = """
            UPDATE sys_sequence
            SET current_value = CASE
                    WHEN (last_reset_key = ? OR (last_reset_key IS NULL AND ? IS NULL)) THEN current_value + ?
                    ELSE ?
                END,
                last_reset_key = ?,
                updated_time = CURRENT_TIMESTAMP
            WHERE id = ?
              AND (tenant_id = ? OR (tenant_id IS NULL AND ? IS NULL))
            """;

    private static final String SQL_BATCH = """
            UPDATE sys_sequence
            SET current_value = CASE
                    WHEN (last_reset_key = ? OR (last_reset_key IS NULL AND ? IS NULL)) THEN current_value + ? * ?
                    ELSE ? + (? - 1) * ?
                END,
                last_reset_key = ?,
                updated_time = CURRENT_TIMESTAMP
            WHERE id = ?
              AND (tenant_id = ? OR (tenant_id IS NULL AND ? IS NULL))
            """;

    private static final String SQL_SELECT_CURRENT = """
            SELECT current_value
            FROM sys_sequence
            WHERE id = ?
              AND (tenant_id = ? OR (tenant_id IS NULL AND ? IS NULL))
            """;

    private final JdbcProxy jdbcProxy;

    @Override
    public SqlParams buildAllocateSql(String currentKey, long step, long startValue, int count, Long id, Long tenantId) {
        SqlParams sqlParams;
        if (count == 1) {
            // SQL_SINGLE placeholders: currentKey, currentKey, step, startValue, currentKey, id, tenantId, tenantId
            sqlParams = new SqlParams(SQL_SINGLE);
            sqlParams.addArgValue(currentKey);
            sqlParams.addArgValue(currentKey);
            sqlParams.addArgValue(step);
            sqlParams.addArgValue(startValue);
            sqlParams.addArgValue(currentKey);
            sqlParams.addArgValue(id);
            sqlParams.addArgValue(tenantId);
            sqlParams.addArgValue(tenantId);
        } else {
            // SQL_BATCH placeholders: currentKey, currentKey, step, count, startValue, count, step, currentKey, id, tenantId, tenantId
            sqlParams = new SqlParams(SQL_BATCH);
            sqlParams.addArgValue(currentKey);
            sqlParams.addArgValue(currentKey);
            sqlParams.addArgValue(step);
            sqlParams.addArgValue(count);
            sqlParams.addArgValue(startValue);
            sqlParams.addArgValue(count);
            sqlParams.addArgValue(step);
            sqlParams.addArgValue(currentKey);
            sqlParams.addArgValue(id);
            sqlParams.addArgValue(tenantId);
            sqlParams.addArgValue(tenantId);
        }
        return sqlParams;
    }

    @Override
    public Long fetchEndValue(String modelName, Long id, Long tenantId) {
        // SQL_SELECT_CURRENT placeholders: id, tenantId, tenantId
        SqlParams sqlParams = new SqlParams(SQL_SELECT_CURRENT);
        sqlParams.addArgValue(id);
        sqlParams.addArgValue(tenantId);
        sqlParams.addArgValue(tenantId);
        return (Long) jdbcProxy.queryForObject(modelName, sqlParams, Long.class);
    }
}
