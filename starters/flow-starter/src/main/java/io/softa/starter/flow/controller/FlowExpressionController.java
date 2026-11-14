package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowExpressionPreview;
import io.softa.starter.flow.dto.FlowExpressionPreviewRequest;
import io.softa.starter.flow.runtime.engine.FlowExpressionPreviewer;

/**
 * Tries an expression or a template over sample variables for the designer, on the engine a flow runs with.
 */
@Tag(name = "Flow Expressions")
@RestController
@RequestMapping("/flow/expressions")
public class FlowExpressionController {

    @PostMapping("/preview")
    @Operation(summary = "Try an expression or a template",
            description = "Evaluates the expression — or, with `template`, interpolates the text's {{ }} placeholders "
                    + "— over the sample `variables`, on the sandboxed engine flows run with. Nothing is read or "
                    + "written. Returns what it gave and its type, or why it did not run.")
    public ApiResponse<FlowExpressionPreview> preview(@RequestBody @Valid FlowExpressionPreviewRequest request) {
        return ApiResponse.success(FlowExpressionPreviewer.preview(request));
    }
}
