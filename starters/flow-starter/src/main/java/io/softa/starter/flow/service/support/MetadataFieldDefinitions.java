package io.softa.starter.flow.service.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.metadata.service.impl.MetadataDtoMapper;

/**
 * {@link RecordFieldDefinitions} by metadata-starter's mapping — the one {@code GET /metadata/getMetaModel} builds
 * a model's fields with — so a definition handed out here is the very one the caller's metadata leaves out.
 *
 * <p>metadata-starter is an optional dependency: this class is registered only when it is on the classpath
 * ({@code FlowAutoConfiguration}), and nothing else in the engine refers to it.
 */
public class MetadataFieldDefinitions implements RecordFieldDefinitions {

    @Override
    public List<Object> definitionsOf(String modelName, Collection<String> fieldNames) {
        List<Object> definitions = new ArrayList<>();
        for (String fieldName : fieldNames) {
            MetaField field = ModelManager.getModelFieldOrNull(modelName, fieldName);
            if (field != null) {
                definitions.add(MetadataDtoMapper.toFieldDTO(field));
            }
        }
        return definitions;
    }
}
