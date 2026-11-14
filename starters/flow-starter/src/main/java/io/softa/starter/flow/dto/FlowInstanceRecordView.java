package io.softa.starter.flow.dto;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The business record a record's approval runs on, as its caller is shown it
 * ({@code GET /flow/instances/{instanceId}/record}, or {@code GET /flow/instances/by-record/record} through
 * whichever of the record's approvals lends it), with the definitions of the fields it shows that the
 * caller's own metadata leaves out — the sensitive fields an approval step of theirs grants — so the client
 * merges them into the models it loaded and draws them like any other field.
 */
@Schema(name = "FlowInstanceRecordView")
public record FlowInstanceRecordView(

        @Schema(description = "The record, as the model read API returns it: relations and options as references, "
                + "masked fields masked, each one-to-many with its rows")
        Map<String, Object> record,

        @Schema(description = "By model name — the record's model, and the model of a one-to-many's rows: the "
                + "definitions of the fields this response shows that the caller's own metadata leaves out "
                + "(PermissionService.getHiddenSensitiveFields), each as the metadata API serves a field "
                + "(MetaFieldDTO). Empty when there are none")
        Map<String, List<Object>> fields
) {
}
