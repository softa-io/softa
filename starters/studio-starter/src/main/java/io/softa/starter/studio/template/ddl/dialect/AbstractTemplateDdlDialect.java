package io.softa.starter.studio.template.ddl.dialect;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.util.StringUtils;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.studio.template.entity.DesignFieldDbMapping;
import io.softa.starter.studio.template.entity.DesignFieldTypeDefault;
import io.softa.starter.studio.template.entity.DesignSqlTemplate;
import io.softa.starter.studio.template.generator.TemplateEngine;
import io.softa.starter.studio.template.generator.DesignGenerationMetadataResolver;
import io.softa.starter.studio.template.enums.DesignCodeLang;
import io.softa.starter.studio.template.entity.DesignCodeTemplate;
import io.softa.starter.studio.template.entity.DesignFieldCodeMapping;
import io.softa.starter.studio.template.ddl.context.FieldDdlCtx;
import io.softa.starter.studio.template.ddl.context.ModelDdlCtx;

/**
 * Shared template-backed DDL dialect support.
 */
public abstract class AbstractTemplateDdlDialect implements DdlDialect {

    private static final DesignGenerationMetadataResolver NOOP_METADATA_RESOLVER = new DesignGenerationMetadataResolver() {
        @Override
        public Map<FieldType, DesignFieldTypeDefault> getFieldTypeDefaults() {
            return Collections.emptyMap();
        }

        @Override
        public Map<FieldType, DesignFieldDbMapping> getFieldDbMappings(DatabaseType databaseType) {
            return Collections.emptyMap();
        }

        @Override
        public Optional<DesignSqlTemplate> getSqlTemplate(DatabaseType databaseType) {
            return Optional.empty();
        }

        @Override
        public Map<FieldType, DesignFieldCodeMapping> getFieldCodeMappings(DesignCodeLang codeLang) {
            return Collections.emptyMap();
        }

        @Override
        public List<DesignCodeTemplate> getCodeTemplates(DesignCodeLang codeLang) {
            return Collections.emptyList();
        }

        @Override
        public List<DesignCodeLang> getAvailableCodeLangs() {
            return Collections.emptyList();
        }
    };

    private final DesignGenerationMetadataResolver metadataResolver;

    protected AbstractTemplateDdlDialect(DesignGenerationMetadataResolver metadataResolver) {
        this.metadataResolver = metadataResolver != null ? metadataResolver : NOOP_METADATA_RESOLVER;
    }

    protected abstract String getTemplateDir();

    protected abstract String getDefaultDbType(FieldType fieldType);

    protected String buildTypeDeclaration(FieldDdlCtx field) {
        if (!StringUtils.hasText(field.getDbType())) {
            return null;
        }
        StringBuilder declaration = new StringBuilder(field.getDbType());
        if (field.getLength() != null && field.getLength() > 0) {
            declaration.append("(").append(field.getLength());
            if (field.getScale() != null && field.getScale() > 0) {
                declaration.append(",").append(field.getScale());
            }
            declaration.append(")");
        }
        return declaration.toString();
    }

    @Override
    public StringBuilder createTableDDL(ModelDdlCtx model) {
        Assert.notEmpty(model.getCreatedFields(),
                "The fields of the model {0} to be published cannot be empty!", model.getModelName());
        Map<String, Object> context = buildContext(model);
        return new StringBuilder(renderSqlTemplate(DesignSqlTemplate::getCreateTableTemplate,
                getTemplateDir() + "CreateTable.peb", context));
    }

    @Override
    public StringBuilder alterTableDDL(ModelDdlCtx model) {
        Map<String, Object> context = buildContext(model);
        return new StringBuilder(renderSqlTemplate(DesignSqlTemplate::getAlterTableTemplate,
                getTemplateDir() + "AlterTable.peb", context));
    }

    @Override
    public StringBuilder dropTableDDL(ModelDdlCtx model) {
        Map<String, Object> context = buildContext(model);
        return new StringBuilder(renderSqlTemplate(DesignSqlTemplate::getDropTableTemplate,
                getTemplateDir() + "DropTable.peb", context));
    }

    @Override
    public StringBuilder alterIndexDDL(ModelDdlCtx model) {
        Map<String, Object> context = buildContext(model);
        return new StringBuilder(renderSqlTemplate(DesignSqlTemplate::getAlterIndexTemplate,
                getTemplateDir() + "AlterIndex.peb", context));
    }

    private Map<String, Object> buildContext(ModelDdlCtx model) {
        prepareModel(model);
        return Map.of("model", model);
    }

    private void prepareModel(ModelDdlCtx model) {
        Map<FieldType, DesignFieldDbMapping> dbMappings = metadataResolver.getFieldDbMappings(getDatabaseType());
        Map<FieldType, DesignFieldTypeDefault> defaults = metadataResolver.getFieldTypeDefaults();
        prepareFields(model.getCreatedFields(), dbMappings, defaults);
        prepareFields(model.getDeletedFields(), dbMappings, defaults);
        prepareFields(model.getUpdatedFields(), dbMappings, defaults);
        prepareFields(model.getRenamedFields(), dbMappings, defaults);
    }

    private void prepareFields(List<FieldDdlCtx> fields,
                               Map<FieldType, DesignFieldDbMapping> dbMappings,
                               Map<FieldType, DesignFieldTypeDefault> defaults) {
        for (FieldDdlCtx field : fields) {
            FieldType fieldType = field.getFieldType();
            if (fieldType == null) {
                continue;
            }
            applyFieldDefaults(field, defaults.get(fieldType));
            if (!StringUtils.hasText(field.getDbType())) {
                field.setDbType(resolveDbType(fieldType, dbMappings));
            }
            field.setTypeDeclaration(buildTypeDeclaration(field));
        }
    }

    private void applyFieldDefaults(FieldDdlCtx field, DesignFieldTypeDefault fieldDefault) {
        if (fieldDefault == null) {
            return;
        }
        if (field.getLength() == null) {
            field.setLength(fieldDefault.getLength());
        }
        if (field.getScale() == null) {
            field.setScale(fieldDefault.getScale());
        }
        if (!StringUtils.hasText(field.getDefaultValue())) {
            field.setDefaultValue(fieldDefault.getDefaultValue());
        }
    }

    private String resolveDbType(FieldType fieldType, Map<FieldType, DesignFieldDbMapping> dbMappings) {
        DesignFieldDbMapping mapping = dbMappings.get(fieldType);
        if (mapping != null && StringUtils.hasText(mapping.getColumnType())) {
            return mapping.getColumnType();
        }
        return getDefaultDbType(fieldType);
    }

    private String renderSqlTemplate(java.util.function.Function<DesignSqlTemplate, String> templateGetter,
                                     String fallbackPath,
                                     Map<String, Object> context) {
        String databaseTemplate = metadataResolver.getSqlTemplate(getDatabaseType())
                .map(templateGetter)
                .orElse(null);
        if (StringUtils.hasText(databaseTemplate)) {
            return TemplateEngine.renderString(databaseTemplate, context);
        }
        return TemplateEngine.render(fallbackPath, context);
    }
}
