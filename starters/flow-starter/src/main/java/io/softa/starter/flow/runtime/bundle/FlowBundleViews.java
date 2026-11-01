package io.softa.starter.flow.runtime.bundle;

import io.softa.starter.flow.dto.FlowBundleDetailView;
import io.softa.starter.flow.dto.FlowBundleSummaryView;
import io.softa.starter.flow.entity.FlowBundle;

/**
 * Engine-side mapper from a {@link CompiledFlowDefinition} to the lightweight
 * {@link FlowBundleSummaryView} list projection, and from a stored bundle to its
 * {@link FlowBundleDetailView}. Kept out of the SDK {@code dto} package so the
 * views stay pure data records.
 */
public final class FlowBundleViews {

    private FlowBundleViews() {
    }

    public static FlowBundleSummaryView summarize(CompiledFlowDefinition def) {
        if (def == null) {
            return null;
        }
        return FlowBundleSummaryView.builder()
                .flowName(def.getFlowName())
                .scenario(def.getScenario())
                .triggerType(def.getTrigger() == null
                        ? null
                        : def.getTrigger().getClass().getSimpleName().replace("Trigger", ""))
                .sync(def.getSync())
                .rollbackOnFail(def.getRollbackOnFail())
                .revision(def.getRevision())
                .bundleId(def.getBundleId())
                .flowId(def.getFlowId())
                .compiledAt(def.getCompiledAt())
                .publishedAt(def.getPublishedAt())
                .nodeCount(def.getNodeIndex() == null ? 0 : def.getNodeIndex().size())
                .transitionCount(def.getTransitionIndex() == null ? 0 : def.getTransitionIndex().size())
                .capabilitySummary(def.getCapabilitySummary())
                .build();
    }

    /**
     * One stored revision as {@code GET /flow/bundles/{bundleId}} returns it: its summary and, with
     * {@code includeDesign}, the design snapshot taken at publish — what re-renders its canvas.
     */
    public static FlowBundleDetailView detail(FlowBundle entity, boolean includeDesign) {
        return new FlowBundleDetailView(summarize(FlowBundleMapper.toDefinition(entity)),
                includeDesign ? entity.getDesignJson() : null);
    }
}
