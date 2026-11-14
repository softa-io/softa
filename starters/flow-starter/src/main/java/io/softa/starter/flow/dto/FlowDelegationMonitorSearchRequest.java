package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;

/**
 * One page of the tenant's delegation rules — every approver's, whoever made them — for the administrators' list,
 * narrowed and sorted the way a metadata-driven table over {@code FlowDelegation} asks. Besides the stored fields, a
 * filter may name {@code status}: no column holds it, so it stands for the condition on a rule's period and
 * cancellation that gives that status now.
 */
@Schema(name = "FlowDelegationMonitorSearchRequest")
public record FlowDelegationMonitorSearchRequest(

        @Schema(description = "Conditions on the stored fields of FlowDelegation, nested with AND / OR, and on status "
                + "— a rule's status now: Scheduled, Active, Ended or Cancelled — with =. Any other field is refused "
                + "(400)")
        Filters filters,

        @Schema(description = "Sort on stored fields of FlowDelegation, e.g. [[\"startTime\", \"DESC\"]]; default: "
                + "newest first")
        Orders orders,

        @Schema(description = "1-based page number; default 1")
        Integer pageNumber,

        @Schema(description = "Page size; default 50")
        Integer pageSize
) {
}
