package io.softa.starter.flow.runtime.nodeconfig;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code GET_RECORD} nodes (bounded, non-paginated lookups).
 * <p>
 * {@code filters} / {@code orders} arrive as raw JSON list / string shapes and are coerced +
 * placeholder-resolved by the executor's type-aware {@code VariableResolver}, not pre-resolved.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GetDataConfig {

    /** Target model name (required). */
    private String modelName;

    /** Retrieval mode code (required): SingleRow / MultiRows / OneFieldValue / ... (see {@code ActionGetDataType}). */
    private String getDataType;

    /** Fields to project; null / empty selects all. */
    private List<String> fields;

    /** Filter condition (raw JSON list / string), coerced to {@code Filters} by the executor. */
    private Object filters;

    /** Sort spec (raw JSON list / string), coerced to {@code Orders} by the executor. */
    private Object orders;

    /** Row cap for multi-row modes. */
    private Integer limitSize;
}
