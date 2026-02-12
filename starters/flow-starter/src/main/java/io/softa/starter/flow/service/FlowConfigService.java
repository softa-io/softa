package io.softa.starter.flow.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.flow.entity.FlowConfig;
import io.softa.starter.flow.message.dto.FlowEventMessage;

/**
 * FlowConfig Model Service Interface
 */
public interface FlowConfigService extends EntityService<FlowConfig, Long> {

    /**
     * Get the flow list by model name.
     *
     * @param modelName model name
     * @return flow configuration list
     */
    List<Map<String, Object>> getByModel(String modelName);

    /**
     * Get the flowConfig by ID, including nodes and edges.
     *
     * @param flowId flow ID
     * @return flowConfig object with nodes and edges
     */
    Optional<FlowConfig> getFlowById(Long flowId);

    /**
     * Execute a non-transactional flow according to the FlowEventMessage.
     *
     * @param eventMessage Flow event message
     * @return Flow execution result
     */
    Object executeFlow(FlowEventMessage eventMessage);

    /**
     * Trigger a transactional Flow according to the FlowEventMessage.
     * The Flow runs in a transaction, and the transaction is rolled back when an exception is thrown internally.
     *
     * @param eventMessage Flow event message
     * @return Flow execution result
     */
    Object executeTransactionalFlow(FlowEventMessage eventMessage);
}