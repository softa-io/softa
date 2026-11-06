package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What submitting a request would do under an approval flow's saved draft — the editor's test run: the route the
 * request would take and who would approve each step, worked out as a record's approval preview is
 * ({@link FlowApprovalPreview}), without running the flow. Nothing is started, written or sent.
 */
@Schema(name = "FlowDesignPreview")
public record FlowDesignPreview(

        @Schema(description = "Whether the draft's submit condition holds for the request. When it does not, a "
                + "submission would not go to this flow; the route is the one it would take if it did")
        boolean submitConditionMet,

        @Schema(description = "The user who submits the request, named")
        FlowRecordApprovalsView.Actor submitter,

        @Schema(description = "The route: the draft's approval steps in order with their approvers, and the nodes it "
                + "passes; or why no approval would start (Refused)")
        FlowApprovalPreview route

) {
}
