package io.softa.starter.studio.template;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.studio.template.entity.DesignCodeTemplate;
import io.softa.starter.studio.template.entity.DesignFieldCodeMapping;
import io.softa.starter.studio.template.entity.DesignFieldDbMapping;
import io.softa.starter.studio.template.entity.DesignFieldTypeDefault;
import io.softa.starter.studio.template.entity.DesignSqlTemplate;
import io.softa.starter.studio.template.enums.DesignCodeLang;
import io.softa.starter.studio.template.generator.DesignGenerationMetadataResolver;

public final class TestMetadataResolver implements DesignGenerationMetadataResolver {

    public static final TestMetadataResolver INSTANCE = new TestMetadataResolver();

    private TestMetadataResolver() {}

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
}
