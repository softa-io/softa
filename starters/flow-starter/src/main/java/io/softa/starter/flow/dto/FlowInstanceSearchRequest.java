package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;

/**
 * Paged instance search for monitoring views. Results are summary rows — the
 * heavy JSON state columns and the trace are excluded; fetch a single instance
 * (or its overlay) for detail. {@code filters} and {@code orders} carry what a
 * metadata-driven table over {@code FlowInstance} asks for, ANDed onto who the
 * search reads for.
 */
@Schema(name = "FlowInstanceSearchRequest")
public record FlowInstanceSearchRequest(

        @Schema(description = "Only the approvals returned to their submitter that no later submission of the record "
                + "replaced — the records still waiting to be edited and submitted again")
        Boolean awaitingRevision,

        @Schema(description = "Conditions on the stored fields of FlowInstance, nested with AND / OR, ANDed onto "
                + "awaitingRevision and onto who the search reads for; `searchName` matches the model's searchName "
                + "fields (title, recordCode). A field the model does not store is refused (400)")
        Filters filters,

        @Schema(description = "Sort on stored fields of FlowInstance, e.g. [[\"createdTime\", \"ASC\"]]; default: "
                + "newest first")
        Orders orders,

        @Schema(description = "1-based page number; default 1")
        Integer pageNumber,

        @Schema(description = "Page size; default 50")
        Integer pageSize
) {
}
