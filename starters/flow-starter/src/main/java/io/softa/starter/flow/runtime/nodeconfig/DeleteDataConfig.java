package io.softa.starter.flow.runtime.nodeconfig;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code DELETE_RECORD} nodes.
 * <p>
 * Rows are targeted by {@code pkVariable} (a {@code {{ var }}} resolving to id(s)) and/or
 * {@code filters}; at least one must resolve to a non-empty condition or the delete is a no-op.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeleteDataConfig {

    /** Target model name (required). */
    private String modelName;

    /** Optional primary-key variable placeholder, e.g. {@code "{{ ids }}"}. */
    private String pkVariable;

    /** Optional filter condition; a raw JSON list / filter string coerced to {@code Filters} by the executor. */
    private Object filters;
}
