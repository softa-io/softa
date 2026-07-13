package io.softa.starter.flow.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.starter.flow.api.CompileDiagnostic;

/**
 * Success-shaped result of validating a design document: diagnostics are the
 * payload, not an error — the editor's lint surface renders them as canvas
 * markers whether or not the document currently compiles.
 */
@Schema(name = "FlowValidationResult")
public record FlowValidationResult(

        @Schema(description = "True when no Error-severity diagnostics remain (Warnings do not block)")
        boolean valid,

        @Schema(description = "All diagnostics, anchored to nodeId/edgeId/field where applicable")
        List<CompileDiagnostic> diagnostics
) {

    public static FlowValidationResult of(List<CompileDiagnostic> diagnostics) {
        boolean valid = diagnostics.stream()
                .noneMatch(d -> d.severity() == CompileDiagnostic.Severity.ERROR);
        return new FlowValidationResult(valid, List.copyOf(diagnostics));
    }
}
