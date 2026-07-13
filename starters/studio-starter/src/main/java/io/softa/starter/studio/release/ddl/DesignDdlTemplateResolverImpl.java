package io.softa.starter.studio.release.ddl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.studio.release.entity.DesignFieldDbMapping;
import io.softa.starter.studio.release.entity.DesignSqlTemplate;
import io.softa.starter.studio.release.service.DesignFieldDbMappingService;
import io.softa.starter.studio.release.service.DesignSqlTemplateService;

/**
 * Default metadata-backed resolver with empty-result fallback semantics.
 */
@Service
public class DesignDdlTemplateResolverImpl implements DesignDdlTemplateResolver {

    private final DesignFieldDbMappingService fieldDbMappingService;
    private final DesignSqlTemplateService sqlTemplateService;

    public DesignDdlTemplateResolverImpl(DesignFieldDbMappingService fieldDbMappingService,
                                                DesignSqlTemplateService sqlTemplateService) {
        this.fieldDbMappingService = fieldDbMappingService;
        this.sqlTemplateService = sqlTemplateService;
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

    private <K, V> Map<K, V> toMap(List<V> values, Function<V, K> keyFunction) {
        return values.stream()
                .filter(value -> keyFunction.apply(value) != null)
                .collect(Collectors.toMap(keyFunction, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }
}
