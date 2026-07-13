package io.softa.starter.flow.runtime.task;

import java.util.Map;

import io.softa.starter.flow.enums.FlowNodeType;

/**
 * SPI for concrete service/data/message task execution.
 */
public interface TaskExecutor {

    FlowNodeType getSupportedNodeType();

    String getExecutor();

    String getName();

    String getDescription();

    Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables);

    /** Config schema describing the input fields for the frontend property panel. */
    default Map<String, Object> getConfigSchema() { return Map.of(); }

    /** Default config values pre-filled when this node is dropped onto the canvas. */
    default Map<String, Object> getDefaultConfig() { return Map.of(); }

    /** Icon identifier for the node palette. */
    default String getIcon() { return null; }

    /** Sort order within the palette group (lower = higher). */
    default int getSortOrder() { return 100; }

    /**
     * False marks a preview/stub executor: hidden from the default palette listing
     * (visible via {@code ?includePreview=true}).
     */
    default boolean isProductionReady() { return true; }
}
