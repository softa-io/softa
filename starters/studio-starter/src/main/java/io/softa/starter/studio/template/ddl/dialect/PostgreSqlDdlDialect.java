package io.softa.starter.studio.template.ddl.dialect;

import java.util.Set;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.studio.template.ddl.context.FieldDdlCtx;
import io.softa.starter.studio.template.ddl.mapping.PostgreSqlDataType;
import io.softa.starter.studio.template.generator.DesignGenerationMetadataResolver;

/**
 * PostgreSQL DDL dialect using Pebble templates.
 */
@Component
public class PostgreSqlDdlDialect extends AbstractTemplateDdlDialect {

    private static final String TEMPLATE_DIR = "templates/sql/postgresql/";
    private static final Set<String> LENGTH_TYPES = Set.of("VARCHAR", "CHAR", "CHARACTER VARYING", "CHARACTER");
    private static final Set<String> NUMERIC_TYPES = Set.of("NUMERIC", "DECIMAL");

    public PostgreSqlDdlDialect(DesignGenerationMetadataResolver metadataResolver) {
        super(metadataResolver);
    }

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.POSTGRESQL;
    }

    @Override
    protected String getTemplateDir() {
        return TEMPLATE_DIR;
    }

    @Override
    protected String getDefaultDbType(FieldType fieldType) {
        return PostgreSqlDataType.getDbType(fieldType);
    }

    @Override
    protected String buildTypeDeclaration(FieldDdlCtx field) {
        String dbType = field.getDbType();
        if (dbType == null) {
            return null;
        }
        String normalized = dbType.trim().toUpperCase();
        StringBuilder declaration = new StringBuilder(dbType);
        if (NUMERIC_TYPES.contains(normalized) && field.getLength() != null && field.getLength() > 0) {
            declaration.append("(").append(field.getLength());
            if (field.getScale() != null && field.getScale() > 0) {
                declaration.append(",").append(field.getScale());
            }
            declaration.append(")");
        } else if (LENGTH_TYPES.contains(normalized) && field.getLength() != null && field.getLength() > 0) {
            declaration.append("(").append(field.getLength()).append(")");
        }
        return declaration.toString();
    }
}
