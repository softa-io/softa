package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Lightweight draft-vs-published status for the editor header
 * (revision badge + "unpublished changes" indicator).
 */
@Schema(name = "FlowDesignStatusView")
public record FlowDesignStatusView(

        @Schema(description = "Revision of the most recent successful publish; null = never published")
        Integer publishedRevision,

        @Schema(description = "Bundle id of the currently active revision; null = never published")
        Long activeBundleId,

        @Schema(description = "True when the draft differs from the most recently published design")
        boolean dirty,

        @Schema(description = "False while the flow is disabled: it starts nothing")
        boolean enabled
) {
}
