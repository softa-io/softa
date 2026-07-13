package io.softa.starter.flow.runtime.bundle;

import io.softa.starter.flow.dto.FlowBundleSummaryView;

/**
 * Engine-side mapper from a {@link CompiledFlowDefinition} to the lightweight
 * {@link FlowBundleSummaryView} list projection. Kept out of the SDK
 * {@code dto} package so the view stays a pure data record.
 */
public final class FlowBundleViews {

    private FlowBundleViews() {
    }

    public static FlowBundleSummaryView summarize(CompiledFlowDefinition def) {
        if (def == null) {
            return null;
        }
        return FlowBundleSummaryView.builder()
                .flowCode(def.getFlowCode())
                .flowName(def.getFlowName())
                .scenario(def.getScenario())
                .triggerType(def.getTrigger() == null
                        ? null
                        : def.getTrigger().getClass().getSimpleName().replace("Trigger", ""))
                .sync(def.getSync())
                .rollbackOnFail(def.getRollbackOnFail())
                .revision(def.getRevision())
                .bundleId(def.getBundleId())
                .designId(def.getDesignId())
                .compiledAt(def.getCompiledAt())
                .publishedAt(def.getPublishedAt())
                .nodeCount(def.getNodeIndex() == null ? 0 : def.getNodeIndex().size())
                .transitionCount(def.getTransitionIndex() == null ? 0 : def.getTransitionIndex().size())
                .capabilitySummary(def.getCapabilitySummary())
                .build();
    }
}
