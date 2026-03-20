package io.softa.starter.flow.node.params;

import io.softa.framework.orm.domain.Filters;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Parameters for deleting data based on model name, primary key and filters.
 * Either the primary key or filters must be provided.
 * When both appear at the same time, merge the primary key and filters with AND.
 * Example:
 * <p>
 * {
 *     "modelName": "DemoModel",
 *     "pkVariable": "{{ ids }}",
 *     "filters": ["active", "=", true]
 * }
 * </p>
 */
@Schema(name = "Delete Data Params")
@Data
@NoArgsConstructor
public class DeleteDataParams implements NodeParams {
    @Schema(description = "The model name of the data to be deleted")
    private String modelName;

    @Schema(description = """
            The primary key variable name of the data to be deleted, supports field name and placeholder {{ var }}.""")
    private String pkVariable;

    @Schema(description = """
            The filters of the data to be deleted. Values support constants, placeholders `{{ expr }}`,
            and reserved field references `{{ @fieldName }}`.""")
    private Filters filters;
}
