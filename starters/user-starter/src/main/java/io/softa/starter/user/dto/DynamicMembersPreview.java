package io.softa.starter.user.dto;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.domain.Page;

/**
 * Who a dynamic rule would grant: how many members it has across the tenant, how many of them lie
 * outside the caller's data scope, and the page of those the caller may see.
 *
 * @param memberCount       every member the rule grants, tenant-wide — what a save would write
 * @param outsideScopeCount members the caller's data scope does not reach: counted, never listed
 * @param visible           a page of the members the caller may see, read with the requested fields
 */
@Schema(description = "A dynamic rule's members: the tenant-wide count, how many fall outside the caller's "
        + "data scope, and a page of those the caller may see")
public record DynamicMembersPreview(
        int memberCount,
        int outsideScopeCount,
        Page<Map<String, Object>> visible
) {
}
