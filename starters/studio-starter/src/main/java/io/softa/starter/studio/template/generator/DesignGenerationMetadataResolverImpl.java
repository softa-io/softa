package io.softa.starter.studio.template.generator;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.studio.template.entity.*;
import io.softa.starter.studio.template.enums.DesignCodeLang;
import io.softa.starter.studio.template.service.*;

/**
 * Default metadata-backed resolver with empty-result fallback semantics.
 */
@Service
public class DesignGenerationMetadataResolverImpl implements DesignGenerationMetadataResolver {

    private final DesignFieldTypeDefaultService fieldTypeDefaultService;
    private final DesignFieldDbMappingService fieldDbMappingService;
    private final DesignSqlTemplateService sqlTemplateService;
    private final DesignFieldCodeMappingService fieldCodeMappingService;
    private final DesignCodeTemplateService codeTemplateService;

    public DesignGenerationMetadataResolverImpl(DesignFieldTypeDefaultService fieldTypeDefaultService,
                                                DesignFieldDbMappingService fieldDbMappingService,
                                                DesignSqlTemplateService sqlTemplateService,
                                                DesignFieldCodeMappingService fieldCodeMappingService,
                                                DesignCodeTemplateService codeTemplateService) {
        this.fieldTypeDefaultService = fieldTypeDefaultService;
        this.fieldDbMappingService = fieldDbMappingService;
        this.sqlTemplateService = sqlTemplateService;
        this.fieldCodeMappingService = fieldCodeMappingService;
        this.codeTemplateService = codeTemplateService;
    }

    @Override
    public Map<FieldType, DesignFieldTypeDefault> getFieldTypeDefaults() {
        return toMap(fieldTypeDefaultService.searchList(new FlexQuery()), DesignFieldTypeDefault::getFieldType);
    }

    @Override
    public Map<FieldType, DesignFieldDbMapping> getFieldDbMappings(DatabaseType databaseType) {
        if (databaseType == null) {
            return Collections.emptyMap();
        }
        Filters filters = new Filters().eq(DesignFieldDbMapping::getDatabaseType, databaseType);
        return toMap(fieldDbMappingService.searchList(new FlexQuery(filters)), DesignFieldDbMapping::getFieldType);
    }

    @Override
    public Optional<DesignSqlTemplate> getSqlTemplate(DatabaseType databaseType) {
        if (databaseType == null) {
            return Optional.empty();
        }
        Filters filters = new Filters().eq(DesignSqlTemplate::getDatabaseType, databaseType);
        return sqlTemplateService.searchOne(new FlexQuery(filters));
    }

    @Override
    public Map<FieldType, DesignFieldCodeMapping> getFieldCodeMappings(DesignCodeLang codeLang) {
        if (codeLang == null) {
            return Collections.emptyMap();
        }
        Filters filters = new Filters().eq(DesignFieldCodeMapping::getCodeLang, codeLang);
        return toMap(fieldCodeMappingService.searchList(new FlexQuery(filters)), DesignFieldCodeMapping::getFieldType);
    }

    @Override
    public List<DesignCodeTemplate> getCodeTemplates(DesignCodeLang codeLang) {
        if (codeLang == null) {
            return Collections.emptyList();
        }
        Filters filters = new Filters().eq(DesignCodeTemplate::getCodeLang, codeLang);
        return codeTemplateService.searchList(new FlexQuery(filters)).stream()
                .sorted(Comparator.comparing(DesignCodeTemplate::getSequence, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(DesignCodeTemplate::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    @Override
    public List<DesignCodeLang> getAvailableCodeLangs() {
        return codeTemplateService.searchList(new FlexQuery()).stream()
                .map(DesignCodeTemplate::getCodeLang)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), ArrayList::new));
    }

    private <K, V> Map<K, V> toMap(java.util.List<V> values, Function<V, K> keyFunction) {
        return values.stream()
                .filter(value -> keyFunction.apply(value) != null)
                .collect(Collectors.toMap(keyFunction, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }
}
