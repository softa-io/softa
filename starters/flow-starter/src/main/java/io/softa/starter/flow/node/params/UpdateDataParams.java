package io.softa.starter.flow.node.params;

import io.softa.framework.orm.domain.Filters;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Parameters for updating data.
 * Update data based on the specified model, primary key or filters, and row template.
 * The value supports constants, variables, and calculation formulas,
 * where dynamic values are represented by `{{ expr }}`.
 * Example:
 * <p>
 * {
 *     "modelName": "SysModel",
 *     "pkVariable": "{{ deptId }}",
 *     "filters": ["code", "=", "{{ deptCode }}"],
 *     "rowTemplate":  {
 *         "parentId": "{{ parentId }}",
 *         "name": "{{ deptName }}",
 *         "ownId": "{{ ownId }}"
 *     }
 * }
 * </p>
 */
@Schema(name = "Update Data Params")
@Data
@NoArgsConstructor
public class UpdateDataParams implements NodeParams {

    @Schema(description = "The model of the data to be updated")
    private String modelName;

    @Schema(description = """
            The primary key variable name of the data to be updated,
            supports single value and multi-value placeholders {{ var }}.""")
    private String pkVariable;

    @Schema(description = """
            The filters of the data to be updated. Values support constants, placeholders {{ expr }},
            and reserved field references {{ @fieldName }}.""")
    private Filters filters;

    @Schema(description = """
            The key-value structure configuration of the updated data.
            The value can be a constant or a placeholder.
            Dynamic values are represented by `{{ expr }}`.
            """)
    private Map<String, Object> rowTemplate;
}
