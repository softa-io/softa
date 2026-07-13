package io.softa.starter.flow.service.impl;

import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.flow.api.FlowCompileException;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.entity.FlowDebugHistory;
import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.runtime.engine.FlowLaunchResponse;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.api.PublishAndStartRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.FlowDebugHistoryService;
import io.softa.starter.flow.service.FlowDesignService;
import io.softa.starter.flow.service.FlowLaunchService;
import io.softa.starter.flow.service.FlowPublishService;

/**
 * Default launch facade built on top of publish and runtime services.
 */
@Slf4j
@Service
public class FlowLaunchServiceImpl implements FlowLaunchService {

    private final FlowPublishService flowPublishService;
    private final FlowRuntimeEngine flowRuntimeEngine;
    private final FlowBundleRegistry flowBundleRegistry;
    private final DefaultFlowCompiler flowCompiler;

    @Autowired(required = false)
    private FlowDebugHistoryService debugHistoryService;

    @Autowired(required = false)
    private FlowDesignService designService;

    public FlowLaunchServiceImpl(FlowPublishService flowPublishService,
                                 FlowRuntimeEngine flowRuntimeEngine,
                                 FlowBundleRegistry flowBundleRegistry,
                                 DefaultFlowCompiler flowCompiler) {
        this.flowPublishService = flowPublishService;
        this.flowRuntimeEngine = flowRuntimeEngine;
        this.flowBundleRegistry = flowBundleRegistry;
        this.flowCompiler = flowCompiler;
    }

    @Override
    public FlowLaunchResponse publishAndStart(PublishAndStartRequest request) {
        if (request == null || request.getDefinition() == null) {
            throw new FlowCompileException("definition is required to publish and start a flow");
        }
        CompiledFlowDefinition published = flowPublishService.publish(request.getDesignId(), request.getDefinition());
        FlowStartRequest startRequest = new FlowStartRequest();
        startRequest.setBundleId(published.getBundleId());
        startRequest.setInitiatorId(request.getInitiatorId());
        startRequest.setVariables(request.getVariables());
        FlowExecutionState state = flowRuntimeEngine.start(startRequest);
        return FlowLaunchResponse.builder()
                .bundle(published)
                .state(state)
                .build();
    }

    @Override
    public FlowLaunchResponse debugRunDraft(Long designId, FlowStartRequest request) {
        if (designService == null) {
            throw new FlowCompileException("FlowDesignService is not available; cannot load draft for a debug run");
        }
        DesignFlowDefinition definition = designService.getById(designId)
                .map(FlowDesign::getDesignJson)
                .orElseThrow(() -> new FlowCompileException("FlowDesign not found: " + designId));

        // compile errors carry anchored diagnostics — same surface as /validate
        CompiledFlowDefinition debugBundle = flowBundleRegistry.registerDebug(
                flowCompiler.compile(definition), definition, designId);

        LocalDateTime startTime = LocalDateTime.now();
        FlowStartRequest startRequest = request != null ? request : new FlowStartRequest();
        startRequest.setDesignId(designId);
        startRequest.setBundleId(debugBundle.getBundleId());
        FlowExecutionState state = flowRuntimeEngine.start(startRequest);

        recordDebugHistory(state, startRequest, startTime);

        return FlowLaunchResponse.builder()
                .bundle(debugBundle)
                .state(state)
                .build();
    }

    private void recordDebugHistory(FlowExecutionState state, FlowStartRequest request, LocalDateTime startTime) {
        if (debugHistoryService == null) {
            return;
        }
        try {
            LocalDateTime endTime = LocalDateTime.now();
            FlowDebugHistory history = new FlowDebugHistory();
            history.setFlowCode(state.getFlowCode());
            history.setFlowRevision(state.getFlowRevision());
            history.setInstanceId(state.getInstanceId());
            history.setStatus(state.getStatus());
            history.setInitiatorId(state.getInitiatorId());
            history.setStartTime(startTime);
            history.setEndTime(endTime);
            history.setDurationMs(Duration.between(startTime, endTime).toMillis());
            history.setEventMessage(JsonUtils.objectToString(request.getVariables()));
            history.setNodeTrace(JsonUtils.objectToString(state.getTrace()));
            history.setFinalVariables(JsonUtils.objectToString(state.getVariables()));
            history.setErrorMessage(state.getErrorMessage());
            debugHistoryService.recordHistory(history);
        } catch (Exception e) {
            log.error("Failed to record debug history: {}", e.getMessage(), e);
        }
    }
}
