package io.softa.starter.message.inbox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;

/**
 * One page of the caller's own notifications, as a metadata-driven table over {@code InboxNotification} asks for it
 * ({@code POST /InboxNotification/mySearch}): its search, column filters and sort, and the page. Whose notifications
 * they are is never the request's to say — the server pins them to the caller.
 */
@Schema(name = "InboxSearchRequest")
public record InboxSearchRequest(

        @Schema(description = "Conditions on the stored fields of InboxNotification, nested with AND / OR, ANDed onto "
                + "the caller's own notifications; `searchName` matches title and content. Any other name — a dotted "
                + "path into another model included — is refused (400)")
        Filters filters,

        @Schema(description = "Sort on stored fields of InboxNotification, e.g. [[\"createdTime\", \"ASC\"]]; "
                + "default: newest first")
        Orders orders,

        @Schema(description = "1-based page number; default 1")
        Integer pageNumber,

        @Schema(description = "Page size; default 50")
        Integer pageSize
) {
}
