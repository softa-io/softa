package io.softa.starter.studio.release.ddl;

import java.util.Map;
import java.util.Optional;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.studio.release.entity.DesignFieldDbMapping;
import io.softa.starter.studio.release.entity.DesignSqlTemplate;

/**
 * Reads the DDL template + field→SQL mapping rows from studio metadata storage, for the JDBC connector's
 * design-backed DDL dialect.
 *
 * <p>Adapted to the DDL renderer SPI by {@link DesignDdlMetadataResolver} only at the connector boundary,
 * so studio metadata does not become a process-wide DDL resolver bean.
 */
public interface DesignDdlTemplateResolver {

    Map<FieldType, DesignFieldDbMapping> getFieldDbMappings(DatabaseType databaseType);

    Optional<DesignSqlTemplate> getSqlTemplate(DatabaseType databaseType);
}
