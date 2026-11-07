package io.softa.starter.flow.dto;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A test run of an approval flow's draft: who submits, and the request they submit — filled in on the spot, never
 * saved.
 */
@Schema(name = "FlowDesignPreviewRequest")
public record FlowDesignPreviewRequest(

        @Schema(description = "The user who submits the request; the current user when empty")
        String submitterId,

        @Schema(description = "The request's field values, in the form they are stored (ids, codes). The employee it "
                + "is about is the submitter's own when the values name none")
        Map<String, Object> values

) {
}
