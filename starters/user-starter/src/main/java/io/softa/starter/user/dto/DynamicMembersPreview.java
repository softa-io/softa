package io.softa.starter.user.dto;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.domain.Page;

/**
 * Who a dynamic rule would grant, within the caller's data scope: how many members, and a page of them.
 * Members outside that scope are neither listed nor counted.
 *
 * @param memberCount the members the rule grants among the employees the caller may read
 * @param visible     a page of those members, read with the requested fields
 */
@Schema(description = "A dynamic rule's members within the caller's data scope: the count and a page of them")
public record DynamicMembersPreview(
        int memberCount,
        Page<Map<String, Object>> visible
) {
}
