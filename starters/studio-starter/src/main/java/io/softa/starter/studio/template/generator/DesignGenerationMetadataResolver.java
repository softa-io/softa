package io.softa.starter.studio.template.generator;

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

/**
 * Resolve generation templates and field mappings from studio metadata storage.
 */
public interface DesignGenerationMetadataResolver {

    Map<FieldType, DesignFieldTypeDefault> getFieldTypeDefaults();

    Map<FieldType, DesignFieldDbMapping> getFieldDbMappings(DatabaseType databaseType);

    Optional<DesignSqlTemplate> getSqlTemplate(DatabaseType databaseType);

    Map<FieldType, DesignFieldCodeMapping> getFieldCodeMappings(DesignCodeLang codeLang);

    List<DesignCodeTemplate> getCodeTemplates(DesignCodeLang codeLang);

    List<DesignCodeLang> getAvailableCodeLangs();
}
