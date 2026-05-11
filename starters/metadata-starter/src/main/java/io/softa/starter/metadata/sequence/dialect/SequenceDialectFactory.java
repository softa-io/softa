package io.softa.starter.metadata.sequence.dialect;

import io.softa.framework.base.exception.ConfigurationException;
import io.softa.framework.orm.jdbc.database.DBUtil;
import io.softa.framework.orm.jdbc.database.dialect.DialectInterface;
import io.softa.framework.orm.jdbc.database.dialect.MySQLDialect;
import io.softa.framework.orm.jdbc.database.dialect.OracleDialect;
import io.softa.framework.orm.jdbc.database.dialect.PostgreSQLDialect;
import io.softa.framework.orm.jdbc.database.dialect.SQLServerDialect;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolve the sequence dialect for the current datasource.
 *
 * <p>PostgreSQL, Oracle and SQL Server all share the portable
 * {@link StandardSequenceDialect} path (UPDATE-then-SELECT under row lock).
 * MySQL has its own dialect that fuses the read into the UPDATE via
 * {@code LAST_INSERT_ID(expr)}. If a future DB diverges from the portable
 * path, split it off into its own dialect class.
 */
@Component
@RequiredArgsConstructor
public class SequenceDialectFactory {

    private final MySqlSequenceDialect mySqlSequenceDialect;
    private final StandardSequenceDialect standardSequenceDialect;

    public SequenceDialect getCurrentDialect() {
        DialectInterface dialect = DBUtil.getDbDialect();
        if (dialect instanceof MySQLDialect) {
            return mySqlSequenceDialect;
        }
        if (dialect instanceof PostgreSQLDialect
                || dialect instanceof OracleDialect
                || dialect instanceof SQLServerDialect) {
            return standardSequenceDialect;
        }
        throw new ConfigurationException("Sequence dialect is not supported for DB dialect: {0}",
                dialect.getClass().getSimpleName());
    }
}
