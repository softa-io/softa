package io.softa.starter.flow.runtime.bundle;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.exception.JSONException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.entity.FlowBundle;

/**
 * Bidirectional mapper between {@link CompiledFlowDefinition} (runtime) and
 * {@link FlowBundle} (persistence entity).
 * <p>
 * The entire compiled flow definition graph is serialized as a single JSON
 * string ({@code compiledJson}) so that the mapping is lossless regardless
 * of future schema additions.
 */
@Slf4j
public final class FlowBundleMapper {

    private FlowBundleMapper() {
    }

    /**
     * Convert a runtime compiled definition to a persistence entity.
     *
     * @param definition       the compiled flow definition
     * @param designDefinition the original design-time definition (may be null)
     * @param existing         an existing entity to update, or {@code null} to create a new one
     * @return the populated entity
     */
    public static FlowBundle toEntity(CompiledFlowDefinition definition,
                                      DesignFlowDefinition designDefinition,
                                      FlowBundle existing) {
        return toEntity(definition, designDefinition, existing, null);
    }

    public static FlowBundle toEntity(CompiledFlowDefinition definition,
                                      DesignFlowDefinition designDefinition,
                                      FlowBundle existing,
                                      Long designId) {
        FlowBundle entity = existing != null ? existing : new FlowBundle();
        entity.setFlowCode(definition.getFlowCode());
        entity.setFlowName(definition.getFlowName());
        entity.setRevision(definition.getRevision());
        entity.setScenario(definition.getScenario());
        entity.setSync(definition.getSync());
        entity.setRollbackOnFail(definition.getRollbackOnFail());
        entity.setCompiledJson(JsonUtils.objectToString(definition));
        entity.setDesignJson(designDefinition);
        entity.setDesignId(designId);
        entity.setCompiledAt(definition.getCompiledAt());
        entity.setPublishedAt(definition.getPublishedAt());
        entity.setActive(true);
        return entity;
    }

    /**
     * Convert a persistence entity back to a runtime compiled definition.
     *
     * @param entity the persistence entity
     * @return the compiled flow definition, or {@code null} if deserialization fails
     */
    public static CompiledFlowDefinition toDefinition(FlowBundle entity) {
        if (entity == null) {
            return null;
        }
        CompiledFlowDefinition def;
        try {
            def = JsonUtils.stringToObject(entity.getCompiledJson(), new TypeReference<>() {});
        } catch (JSONException e) {
            log.warn("FlowBundleMapper: failed to deserialize compiledJson for bundle id={}", entity.getId(), e);
            return null;
        }
        if (def == null) {
            return null;
        }
        // Stamp the bundle-level identity fields that are not part of the compiled JSON payload.
        def.setBundleId(entity.getId());
        def.setDesignId(entity.getDesignId());
        def.setTenantId(entity.getTenantId());
        // parsedConfig is @JsonIgnore — rebuild the typed config for every node,
        // otherwise handlers pattern-matching on it fail after a registry reload.
        if (def.getNodeIndex() != null) {
            def.getNodeIndex().values().forEach(node ->
                    node.setParsedConfig(NodeConfigParser.parse(node.getType(), node.getConfig())));
        }
        return def;
    }

}


