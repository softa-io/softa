package io.softa.starter.studio.template.ddl.dialect;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.ConfigurationException;
import io.softa.framework.orm.enums.DatabaseType;

/**
 * Resolves DDL dialect implementations by database type.
 */
@Component
public class DdlDialectRegistry {

    private final Map<DatabaseType, DdlDialect> dialects = new EnumMap<>(DatabaseType.class);

    public DdlDialectRegistry(List<DdlDialect> dialectImplementations) {
        for (DdlDialect dialect : dialectImplementations) {
            DdlDialect existing = dialects.putIfAbsent(dialect.getDatabaseType(), dialect);
            if (existing != null) {
                throw new ConfigurationException("Duplicate DDL dialect {0} found: {1}, {2}",
                        dialect.getDatabaseType(), existing.getClass().getName(), dialect.getClass().getName());
            }
        }
    }

    public DdlDialect getDialect(DatabaseType databaseType) {
        DdlDialect dialect = dialects.get(databaseType);
        if (dialect == null) {
            throw new ConfigurationException("DDL dialect of database {0} is not currently supported!", databaseType);
        }
        return dialect;
    }
}
