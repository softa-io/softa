package io.softa.starter.user.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * Body of {@code POST /Role/previewDynamicMembers}: a membership rule as the wizard would save it,
 * and which page of its members to show.
 */
@Schema(description = "A dynamic membership rule to preview, and the page of its members to return")
public record DynamicMembersPreviewDTO(
        @Schema(description = "The rule, in the Filters array form Role.dynamicFilter stores")
        JsonNode dynamicFilter,

        @Schema(description = "Employee fields to read for each member; the id is always read")
        List<String> fields,

        @Schema(description = "Page number, from 1")
        Integer pageNumber,

        @Schema(description = "Page size")
        Integer pageSize
) {
}
