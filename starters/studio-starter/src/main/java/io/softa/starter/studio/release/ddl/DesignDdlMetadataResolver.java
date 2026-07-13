package io.softa.starter.studio.release.ddl;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.metadata.ddl.spi.BuiltinDdlMetadataResolver;
import io.softa.starter.metadata.ddl.spi.DdlMetadataResolver;
import io.softa.starter.metadata.ddl.spi.DdlTemplateBundle;
import io.softa.starter.metadata.ddl.spi.FieldDdlDefault;
import io.softa.starter.studio.release.entity.DesignFieldDbMapping;
import io.softa.starter.studio.release.entity.DesignSqlTemplate;

/**
 * Adapts studio's generation catalog to the DDL renderer SPI.
 *
 * <p>Only connector code should create this adapter. That keeps
 * {@link DesignDdlTemplateResolver} focused on studio data access and
 * avoids registering a second process-wide {@link DdlMetadataResolver}.
 */
public final class DesignDdlMetadataResolver implements DdlMetadataResolver {

    private final DesignDdlTemplateResolver designResolver;

    public DesignDdlMetadataResolver(DesignDdlTemplateResolver designResolver) {
        if (designResolver == null) {
            throw new IllegalArgumentException("designResolver must not be null");
        }
        this.designResolver = designResolver;
    }

    @Override
    public Map<FieldType, String> getColumnTypes(DatabaseType databaseType) {
        Map<FieldType, DesignFieldDbMapping> mappings = designResolver.getFieldDbMappings(databaseType);
        if (mappings == null || mappings.isEmpty()) {
            return Map.of();
        }
        return mappings.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().getColumnType() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getColumnType()));
    }

    @Override
    public Map<FieldType, FieldDdlDefault> getFieldDefaults() {
        // Per-FieldType length/scale defaults are builtin Code, not design rows.
        return BuiltinDdlMetadataResolver.builtinFieldDefaults();
    }

    @Override
    public Optional<DdlTemplateBundle> getDdlTemplates(DatabaseType databaseType) {
        Optional<DesignSqlTemplate> template = designResolver.getSqlTemplate(databaseType);
        if (template.isEmpty()) {
            return Optional.empty();
        }
        return template.map(t -> new DdlTemplateBundle(
                t.getCreateTableTemplate(),
                t.getAlterTableTemplate(),
                t.getDropTableTemplate(),
                t.getAlterIndexTemplate()));
    }
}
