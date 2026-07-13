package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Editor request accompanying a publish. The draft's stored designJson is the
 * single source of truth — no canvas payload is accepted here.
 */
@Schema(name = "FlowPublishRequest")
public record FlowPublishRequest(

        @Schema(description = "Human-readable description of what changed in this revision")
        String changeDescription
) {
}
