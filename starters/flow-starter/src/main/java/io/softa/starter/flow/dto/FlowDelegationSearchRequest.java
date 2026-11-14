package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.starter.flow.enums.FlowDelegationBox;

/**
 * One page of the caller's own delegation rules, narrowed and sorted the way a metadata-driven table over
 * {@code FlowDelegation} asks. The rows are always the caller's: the box and the caller are a constraint the filters
 * are ANDed onto, never one they replace.
 */
@Schema(name = "FlowDelegationSearchRequest")
public record FlowDelegationSearchRequest(

        @Schema(description = "Which of the caller's rules: Mine — the rules routing their own tasks, whatever their "
                + "status; ToMe — the rules routing others' tasks to them, now or later (neither cancelled nor ended)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        FlowDelegationBox box,

        @Schema(description = "Conditions on the stored fields of FlowDelegation, nested with AND / OR, ANDed onto "
                + "the box. A field the model does not store is refused (400)")
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
