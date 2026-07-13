package io.softa.starter.flow.runtime.nodeconfig;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code QUERY_RECORDS} nodes (paginated query).
 * <p>
 * {@code filters} / {@code orders} arrive as raw JSON shapes and are coerced + placeholder-resolved
 * by the executor's type-aware {@code VariableResolver}, not pre-resolved.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryRecordsConfig {

    /** Target model name (required). */
    private String modelName;

    /** Fields to project; null / empty selects all. */
    private List<String> fields;

    /** Filter condition (raw JSON list / string), coerced to {@code Filters} by the executor. */
    private Object filters;

    /** Sort spec (raw JSON list / string), coerced to {@code Orders} by the executor. */
    private Object orders;

    /** 1-based page number (default 1). */
    private Integer page;

    /** Page size (default 50). */
    private Integer pageSize;
}
