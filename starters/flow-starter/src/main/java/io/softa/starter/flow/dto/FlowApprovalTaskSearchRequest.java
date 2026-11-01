package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.starter.flow.enums.FlowApprovalTaskBox;

/**
 * One page of the caller's own tasks in a box of the approval center, narrowed and sorted the way a
 * metadata-driven table over {@code FlowApprovalTask} asks. The rows are always the caller's: the box and the caller
 * are a constraint the filters are ANDed onto, never one they replace.
 */
@Schema(name = "FlowApprovalTaskSearchRequest")
public record FlowApprovalTaskSearchRequest(

        @Schema(description = "Which of the caller's tasks: Pending — approval tasks waiting on them; Handled — "
                + "approval tasks that closed; Cc — CC tasks, read and unread", requiredMode = Schema.RequiredMode.REQUIRED)
        FlowApprovalTaskBox box,

        @Schema(description = "Conditions on the stored fields of FlowApprovalTask, nested with AND / OR, ANDed onto "
                + "the box; `searchName` matches the model's searchName fields (instanceTitle, recordCode). A field "
                + "the model does not store is refused (400)")
        Filters filters,

        @Schema(description = "Sort on stored fields of FlowApprovalTask, e.g. [[\"submittedTime\", \"DESC\"]]; "
                + "default: the box's own order")
        Orders orders,

        @Schema(description = "1-based page number; default 1")
        Integer pageNumber,

        @Schema(description = "Page size; default 50")
        Integer pageSize
) {
}
