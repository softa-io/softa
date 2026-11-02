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
     * One stored revision as the revision history lists it: its compiled summary, with what only the row knows —
     * whether it is the flow's effective revision ({@code active}) and its {@code changeDescription}. A row whose
     * compiled definition cannot be read is summarized from its own columns.
     */
    public static FlowBundleSummaryView summarize(FlowBundle entity) {
        if (entity == null) {
            return null;
        }
        FlowBundleSummaryView summary = summarize(FlowBundleMapper.toDefinition(entity));
        if (summary == null) {
            summary = FlowBundleSummaryView.builder()
                    .flowName(entity.getFlowName())
                    .scenario(entity.getScenario())
                    .sync(entity.getSync())
                    .rollbackOnFail(entity.getRollbackOnFail())
                    .revision(entity.getRevision())
                    .bundleId(entity.getId())
                    .flowId(entity.getFlowId())
                    .compiledAt(entity.getCompiledAt())
                    .build();
        }
        summary.setPublishedAt(entity.getPublishedAt() != null ? entity.getPublishedAt() : summary.getPublishedAt());
        summary.setActive(Boolean.TRUE.equals(entity.getActive()));
        summary.setChangeDescription(entity.getChangeDescription());
        return summary;
    }

    /**
     * One stored revision as {@code GET /flow/bundles/{bundleId}} returns it: its summary and, with
     * {@code includeDesign}, the design snapshot taken at publish — what re-renders its canvas.
     */
    public static FlowBundleDetailView detail(FlowBundle entity, boolean includeDesign) {
        return new FlowBundleDetailView(summarize(entity), includeDesign ? entity.getDesignJson() : null);
    }
}
