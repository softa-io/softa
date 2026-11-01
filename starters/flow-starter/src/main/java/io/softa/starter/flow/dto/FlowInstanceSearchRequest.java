package io.softa.starter.flow.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Paged instance search for monitoring views. Results are summary rows — the
 * heavy JSON state columns and the trace are excluded; fetch a single instance
 * (or its overlay) for detail. Besides the typed filters, {@code filters} and
 * {@code orders} carry what a metadata-driven table over {@code FlowInstance}
 * asks for, ANDed onto everything else.
 */
@Schema(name = "FlowInstanceSearchRequest")
public record FlowInstanceSearchRequest(

        @Schema(description = "Filter by flow id")
        String flowId,

        @Schema(description = "Filter by execution status")
        FlowExecutionStatus status,

        @Schema(description = "Filter by initiator id. Honored only by the monitor endpoint "
                + "(POST /flow/monitor/instances/search); POST /flow/instances/search always "
                + "overrides it with the authenticated caller")
        String initiatorId,

        @Schema(description = "Filter by related model name")
        String modelName,

        @Schema(description = "Filter by related row id (requires modelName)")
        String rowId,

        @Schema(description = "Filter by creation time — lower bound, inclusive")
        LocalDateTime createdFrom,

        @Schema(description = "Filter by creation time — upper bound, inclusive")
        LocalDateTime createdTo,

        @Schema(description = "Filter a record's approvals by the employee the record is about")
        Long subjectId,

        @Schema(description = "Filter a record's approvals by the subject's department at submission")
        Long subjectDeptId,

        @Schema(description = "Filter a record's approvals by the date the record takes effect — lower bound, inclusive")
        LocalDate effectiveDateFrom,

        @Schema(description = "Filter a record's approvals by the date the record takes effect — upper bound, inclusive")
        LocalDate effectiveDateTo,

        @Schema(description = "Filter a record's approvals by the record's own number (contains)")
        String recordCode,

        @Schema(description = "Only the approvals returned to their submitter that no later submission of the record "
                + "replaced — the records still waiting to be edited and submitted again")
        Boolean awaitingRevision,

        @Schema(description = "Conditions on the stored fields of FlowInstance, nested with AND / OR, ANDed onto every "
                + "other filter and onto who the search reads for; `searchName` matches the model's searchName fields "
                + "(title, recordCode). A field the model does not store is refused (400)")
        Filters filters,

        @Schema(description = "Sort on stored fields of FlowInstance, e.g. [[\"createdTime\", \"ASC\"]]; default: "
                + "newest first")
        Orders orders,

        @Schema(description = "1-based page number; default 1")
        Integer pageNumber,

        @Schema(description = "Page size; default 50")
        Integer pageSize
) {

    /** A search by flow and status alone, of the first page. */
    public static FlowInstanceSearchRequest of(String flowId, FlowExecutionStatus status) {
        return new FlowInstanceSearchRequest(flowId, status, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
