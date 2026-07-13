package io.softa.starter.flow.design;

import java.util.List;

/**
 * SPI for contributing {@link FlowNodeDescriptor}s to the registry.
 * Implementations are collected by Spring and aggregated in
 * {@code FlowNodeDescriptorRegistry}.
 */
public interface FlowNodeDescriptorProvider {

    List<FlowNodeDescriptor> getDescriptors();
}
