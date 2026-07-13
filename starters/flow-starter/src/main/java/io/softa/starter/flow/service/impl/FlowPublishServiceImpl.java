package io.softa.starter.flow.service.impl;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.dto.FlowBundleSummaryView;
import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleMapper;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.bundle.FlowBundleViews;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.service.FlowBundleService;
import io.softa.starter.flow.service.FlowDesignService;
import io.softa.starter.flow.service.FlowPublishService;

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
    public FlowBundleSummaryView publishDraft(Long designId, String changeDescription) {
        if (designService == null) {
            throw new FlowRuntimeException("FlowDesignService is not available; cannot load draft for publish");
        }
        DesignFlowDefinition definition = designService.getById(designId)
                .map(FlowDesign::getDesignJson)
                .orElseThrow(() -> new FlowRuntimeException("FlowDesign not found: " + designId));
        CompiledFlowDefinition compiled = publish(designId, definition);
        if (bundleService != null && StringUtils.hasText(changeDescription)) {
            bundleService.findById(compiled.getBundleId()).ifPresent(bundle -> {
                bundle.setChangeDescription(changeDescription);
                bundleService.saveBundle(bundle);
            });
        }
        return FlowBundleViews.summarize(compiled);
    }

    @Override
    public CompiledFlowDefinition publish(Long designId, DesignFlowDefinition definition) {
        CompiledFlowDefinition compiled = bundleRegistry.register(
                flowCompiler.compile(definition), definition, designId);
        if (designService != null && designId != null) {
            designService.upsertFromPublish(designId, compiled.getRevision());
        }
        return compiled;
    }

    @Override
    public Optional<CompiledFlowDefinition> getLatest(Long designId) {
        return bundleRegistry.getActiveByDesignId(designId);
    }

    @Override
    public List<CompiledFlowDefinition> getRevisions(Long designId) {
        return bundleRegistry.listRevisionsByDesignId(designId);
    }

    @Override
    public Optional<CompiledFlowDefinition> activateBundle(Long bundleId) {
        if (bundleService == null) {
            // In-memory only: the registry is authoritative and already holds the bundle
            return bundleRegistry.getByBundleId(bundleId);
        }
        return bundleService.activateBundle(bundleId)
                .map(entity -> {
                    bundleRegistry.refreshActiveForDesignId(entity.getDesignId());
                    CompiledFlowDefinition def = FlowBundleMapper.toDefinition(entity);
                    if (def != null && entity.getId() != null) {
                        return bundleRegistry.getByBundleId(entity.getId()).orElse(def);
                    }
                    return def;
                });
    }

}
