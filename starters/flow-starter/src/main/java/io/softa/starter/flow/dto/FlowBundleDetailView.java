package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.starter.flow.design.DesignFlowDefinition;

/**
 * Detail view of one published bundle. {@code design} is the design-time snapshot
 * taken at publish — the only representation that can fully re-render the canvas
 * (the compiled graph drops node width/height/data and edge label/type).
 */
@Schema(name = "FlowBundleDetailView")
public record FlowBundleDetailView(

        @Schema(description = "Bundle summary")
        FlowBundleSummaryView summary,

        @Schema(description = "Design snapshot at publish time; present only when requested via include=design")
        DesignFlowDefinition design
) {
}
