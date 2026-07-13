package io.softa.starter.studio.release.ddl;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.metadata.ddl.spi.DdlMetadataResolver;
import io.softa.starter.studio.release.entity.DesignFieldDbMapping;
import io.softa.starter.studio.release.entity.DesignSqlTemplate;
import io.softa.starter.studio.release.ddl.DesignDdlMetadataResolver;
import io.softa.starter.studio.release.ddl.DesignDdlTemplateResolver;

public final class TestMetadataResolver implements DesignDdlTemplateResolver {

    public static final TestMetadataResolver INSTANCE = new TestMetadataResolver();
    public static final DdlMetadataResolver DDL = new DesignDdlMetadataResolver(INSTANCE);

    private TestMetadataResolver() {}

    @Override
    public Map<FieldType, DesignFieldDbMapping> getFieldDbMappings(DatabaseType databaseType) {
        return Collections.emptyMap();
    }

    @Override
    public Optional<DesignSqlTemplate> getSqlTemplate(DatabaseType databaseType) {
        return Optional.empty();
    }
}
