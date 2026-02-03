package io.softa.framework.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.framework.web.dto.ModelField;
import io.softa.framework.web.dto.ModelFields;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.service.ToolkitService;

/**
 * Toolkit controller
 */
@Tag(name = "Toolkit")
@RestController
@RequestMapping("/toolkit")
public class ToolkitController {

    @Autowired
    private ToolkitService toolkitService;

    /**
     * Recompute the stored calculation fields, including computed and cascaded fields.
     *
     * @param modelFields the model name and the list of fields to be recomputed
     */
    @Operation(description = """
            Recomputes the stored computed and cascaded fields for the specified model.
            If fields are not specified, all stored computed and cascaded fields are recomputed by default.""")
    @PostMapping("/recompute")
    public ApiResponse<Boolean> recompute(@RequestBody @Valid ModelFields modelFields) {
        toolkitService.recompute(modelFields.getModel(), modelFields.getFields());
        return ApiResponse.success(true);
    }

    /**
     * Encrypts historical plaintext data after the field is set to `encrypted=true`.
     *
     * @param modelField the model name and the field name for which historical data needs to be encrypted
     * @return the number of rows fixed
     */
    @Operation(description = """
            After changing field to encrypted field, call this API to encrypt historical plaintext data.
            The field name cannot be empty. Returns the number of rows fixed.""")
    @PostMapping("/fixUnencryptedData")
    public ApiResponse<Long> fixUnencryptedData(@RequestBody @Valid ModelField modelField) {
        Long result = toolkitService.fixUnencryptedData(modelField.getModel(), modelField.getField());
        return ApiResponse.success(result);
    }

    /**
     * Validate the expression.
     *
     * @param expression the expression to be validated
     * @return True if the expression is valid, otherwise return the error message
     */
    @Operation(description = "Validates the expression.")
    @GetMapping("/validateExpression")
    @Parameter(name = "expression", description = "The expression to be validated", schema = @Schema(type = "string"), required = true)
    public ApiResponse<Boolean> validateExpression(@RequestParam String expression) {
        if (StringTools.isExpression(expression)) {
            expression = expression.substring(2, expression.length() - 1);
        }
        boolean isValid = ComputeUtils.validateExpression(expression);
        return ApiResponse.success(isValid);
    }
}