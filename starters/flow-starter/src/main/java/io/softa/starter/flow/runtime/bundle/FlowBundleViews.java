package io.softa.starter.flow.runtime.bundle;

import io.softa.starter.flow.dto.FlowBundleDetailView;
import io.softa.starter.flow.dto.FlowBundleSummaryView;
import io.softa.starter.flow.entity.FlowBundle;

/**
 * Engine-side mapper from a {@link CompiledFlowDefinition} — or a stored bundle, which adds whether it is
 * active and its change description — to the lightweight {@link FlowBundleSummaryView} list projection, and
 * from a stored bundle to its {@link FlowBundleDetailView}. Kept out of the SDK {@code dto} package so the
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
                .revision(def.getRevision())
                .bundleId(def.getBundleId())
                .flowId(def.getFlowId())
                .publishedAt(def.getPublishedAt())
                .build();
    }

    /**
     * One stored revision as the revision history lists it, from its own columns — its compiled definition is not
     * read: what it is, whether it is the flow's effective revision ({@code active}) and its {@code changeDescription}.
     */
    public static FlowBundleSummaryView summarize(FlowBundle entity) {
        if (entity == null) {
            return null;
        }
        return FlowBundleSummaryView.builder()
                .flowName(entity.getFlowName())
                .scenario(entity.getScenario())
                .revision(entity.getRevision())
                .bundleId(entity.getId())
                .flowId(entity.getFlowId())
                .publishedAt(entity.getPublishedAt())
                .active(Boolean.TRUE.equals(entity.getActive()))
                .changeDescription(entity.getChangeDescription())
                .build();
    }

    /**
     * One stored revision as {@code GET /flow/bundles/{bundleId}} returns it: its summary and, with
     * {@code includeDesign}, the design snapshot taken at publish — what re-renders its canvas.
     */
    public static FlowBundleDetailView detail(FlowBundle entity, boolean includeDesign) {
        return new FlowBundleDetailView(summarize(entity), includeDesign ? entity.getDesignJson() : null);
    }
}
