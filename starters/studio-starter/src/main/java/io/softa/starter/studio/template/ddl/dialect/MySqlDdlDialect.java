package io.softa.starter.studio.template.ddl.dialect;

import org.springframework.stereotype.Component;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.studio.template.ddl.mapping.MySqlDataType;
import io.softa.starter.studio.template.generator.DesignGenerationMetadataResolver;

/**
 * MySQL DDL dialect using Pebble templates.
 */
@Component
public class MySqlDdlDialect extends AbstractTemplateDdlDialect {

    private static final String TEMPLATE_DIR = "templates/sql/mysql/";

    public MySqlDdlDialect(DesignGenerationMetadataResolver metadataResolver) {
        super(metadataResolver);
    }

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.MYSQL;
    }

    @Override
    protected String getTemplateDir() {
        return TEMPLATE_DIR;
    }

    @Override
    protected String getDefaultDbType(FieldType fieldType) {
        return MySqlDataType.getDbType(fieldType);
    }
}
