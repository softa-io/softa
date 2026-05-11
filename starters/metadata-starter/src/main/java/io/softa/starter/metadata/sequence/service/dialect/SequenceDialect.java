package io.softa.starter.metadata.sequence.service.dialect;

import io.softa.framework.orm.jdbc.database.SqlParams;

/**
 * Dialect contract for sequence allocation SQL.
 *
 * <p>Implementations must be invoked inside a transaction that holds the
 * exclusive row lock on the target {@code sys_sequence} row until commit
 * (typically REQUIRES_NEW or MANDATORY in the caller). The
 * {@link #fetchEndValue} read relies on seeing the same transaction's own
 * UPDATE, so callers must not split the UPDATE and the read across
 * transactions or connections.
 */
public interface SequenceDialect {

    SqlParams buildAllocateSql(String currentKey, long step, long startValue, int count, Long id, Long tenantId);

    Long fetchEndValue(String modelName, Long id, Long tenantId);
}
