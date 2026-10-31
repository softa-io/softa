package io.softa.starter.flow.service.impl;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.dto.FlowBundleSummaryView;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleMapper;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.bundle.FlowBundleViews;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.service.FlowBundleService;
import io.softa.starter.flow.service.FlowDesignService;
import io.softa.starter.flow.service.FlowPublishService;
import io.softa.starter.flow.service.support.ComputeOutputs;
import io.softa.starter.flow.service.support.SubmitConditions;

/**
 * Default publish service that compiles a design definition and registers the result.
 */
@Service
public class FlowPublishServiceImpl implements FlowPublishService {

    private final DefaultFlowCompiler flowCompiler;
    private final FlowBundleRegistry bundleRegistry;

    @Autowired(required = false)
    private FlowBundleService bundleService;

    @Autowired(required = false)
    private FlowDesignService designService;

    public FlowPublishServiceImpl(DefaultFlowCompiler flowCompiler, FlowBundleRegistry bundleRegistry) {
        this.flowCompiler = flowCompiler;
        this.bundleRegistry = bundleRegistry;
    }

    @Override
    public FlowBundleSummaryView publishDraft(String flowId, String changeDescription) {
        if (designService == null) {
            throw new FlowRuntimeException("FlowDesignService is not available; cannot load draft for publish");
        }
        // The design's scenario column is the source of truth: compile the document aligned to it.
        DesignFlowDefinition definition = designService.getById(flowId)
                .map(design -> FlowDesignServiceImpl.alignedDefinition(design.getDesignJson(), design))
                .orElseThrow(() -> new FlowRuntimeException("FlowDesign not found: " + flowId));
        CompiledFlowDefinition compiled = publish(flowId, definition);
        if (bundleService != null && StringUtils.hasText(changeDescription)) {
            bundleService.findById(compiled.getBundleId()).ifPresent(bundle -> {
                bundle.setChangeDescription(changeDescription);
                bundleService.saveBundle(bundle);
            });
        }
        return FlowBundleViews.summarize(compiled);
    }

    @Override
    public CompiledFlowDefinition publish(String flowId, DesignFlowDefinition definition) {
        CompiledFlowDefinition candidate = flowCompiler.compile(definition);
        if (isEnabled(flowId)) {
            requireCompatible(flowId, candidate);
        }
        CompiledFlowDefinition compiled = bundleRegistry.register(candidate, definition, flowId);
        if (designService != null && flowId != null) {
            designService.upsertFromPublish(flowId, compiled.getRevision());
        }
        return compiled;
    }

    @Override
    public Optional<CompiledFlowDefinition> activateBundle(Long bundleId) {
        bundleRegistry.getByBundleId(bundleId)
                .filter(target -> isEnabled(target.getFlowId()))
                .ifPresent(target -> requireCompatible(target.getFlowId(), target));
        if (bundleService == null) {
            // In-memory only: the registry is authoritative and already holds the bundle
            return bundleRegistry.getByBundleId(bundleId);
        }
        return bundleService.activateBundle(bundleId)
                .map(entity -> {
                    bundleRegistry.refreshActiveForFlowId(entity.getFlowId());
                    CompiledFlowDefinition def = FlowBundleMapper.toDefinition(entity);
                    if (def != null && entity.getId() != null) {
                        return bundleRegistry.getByBundleId(entity.getId()).orElse(def);
                    }
                    return def;
                });
    }

    /**
     * What {@code candidate} would run beside the enabled flows of its model: a Compute output nobody else declares,
     * and an approval's records no other approval flow of its document takes by the same condition.
     */
    private void requireCompatible(String flowId, CompiledFlowDefinition candidate) {
        List<CompiledFlowDefinition> enabled = bundleRegistry.listTriggerCandidates(candidate.getModelName());
        ComputeOutputs.requireOwn(flowId, candidate, enabled);
        SubmitConditions.requireDistinct(flowId, candidate, enabled);
    }

    /** Whether the flow runs once published: a flow without a design row (an in-memory registry's) always does. */
    private boolean isEnabled(String flowId) {
        return designService == null || flowId == null || designService.getById(flowId)
                .map(design -> !Boolean.FALSE.equals(design.getEnabled()))
                .orElse(true);
    }
}
