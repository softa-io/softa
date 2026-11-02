package io.softa.starter.flow.dto;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import io.softa.starter.flow.api.CompiledFlowCapabilitySummary;
import io.softa.starter.flow.enums.FlowScenario;

/**
 * Lightweight summary of a compiled flow bundle for list endpoints.
 * Excludes the heavy graph topology ({@code nodeIndex}, {@code transitionIndex})
 * that {@code CompiledFlowDefinition} carries — fetch the full definition via the
 * detail endpoint when needed. Built engine-side by {@code FlowBundleViews.summarize}: from the stored
 * revision ({@code FlowBundle}) wherever there is one, which alone knows whether it is {@code active} and
 * its {@code changeDescription}.
 */
@Data
@Builder
@Schema(name = "FlowBundleSummaryView")
public class FlowBundleSummaryView {

    @Schema(description = "Flow name")
    private String flowName;

    @Schema(description = "Execution scenario")
    private FlowScenario scenario;

    @Schema(description = "Trigger type discriminator (e.g. EntityChange, Api, Cron)")
    private String triggerType;

    @Schema(description = "Whether the flow executes synchronously within the triggering transaction")
    private Boolean sync;

    @Schema(description = "Whether to roll back the triggering transaction on flow failure")
    private Boolean rollbackOnFail;

    @Schema(description = "Published revision number")
    private Integer revision;

    @Schema(description = "Bundle id")
    private Long bundleId;

    @Schema(description = "Flow ID")
    private String flowId;

    @Schema(description = "Compile timestamp")
    private LocalDateTime compiledAt;

    @Schema(description = "Publish timestamp")
    private LocalDateTime publishedAt;

    @Schema(description = "Whether this is the flow's effective revision — the one new instances start from while "
            + "the flow is enabled; one per flow")
    private Boolean active;

    @Schema(description = "What changed in this revision, as written when it was published")
    private String changeDescription;

    @Schema(description = "Total node count")
    private Integer nodeCount;

    @Schema(description = "Total transition count")
    private Integer transitionCount;

    @Schema(description = "Derived capability summary (lightweight)")
    private CompiledFlowCapabilitySummary capabilitySummary;
}
