package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Editor request to duplicate an existing flow draft under a fresh flow code.
 * Replaces the generic copy API, which is disabled on {@code FlowDesign}
 * because a verbatim copy would collide on the unique flow code.
 */
@Schema(name = "FlowDesignDuplicateRequest")
public record FlowDesignDuplicateRequest(

        @Schema(description = "Flow code for the copy; derived from the source code when omitted")
        String newFlowCode,

        @Schema(description = "Display name for the copy; derived from the source name when omitted")
        String newFlowName
) {
}
