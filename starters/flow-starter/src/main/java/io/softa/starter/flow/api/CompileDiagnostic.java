package io.softa.starter.flow.api;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A single structured compilation diagnostic produced by a {@code FlowValidator}.
 *
 * <p>Diagnostics allow the frontend to highlight the exact edge, node, or field that
 * caused a compilation error, rather than showing only a plain string message.</p>
 *
 * @param severity {@link Severity#ERROR} blocks publishing; {@link Severity#WARNING} is advisory
 * @param edgeId   the affected edge id, or {@code null} for non-edge errors
 * @param nodeId   the affected node id, or {@code null} for flow-level errors
 * @param nodeType the node type display name (e.g. {@code "Approval"}), or {@code null}
 * @param field    the config field path that failed (e.g. {@code "config.approvers"}),
 *                 or {@code null} when the error applies to the whole node
 * @param code     machine-readable error code (e.g. {@code "BLANK_APPROVER"})
 * @param message  human-readable description
 */
@Schema(name = "CompileDiagnostic")
public record CompileDiagnostic(

        @Schema(description = "Error blocks publishing; Warning is advisory")
        Severity severity,

        @Schema(description = "Affected edge id; null for non-edge errors")
        String edgeId,

        @Schema(description = "Affected node id; null for flow-level errors")
        String nodeId,

        @Schema(description = "Node type display name; null for flow-level errors")
        String nodeType,

        @Schema(description = "Config field path that failed, e.g. config.approvers; null for node-level errors")
        String field,

        @Schema(description = "Machine-readable error code, e.g. BLANK_APPROVER")
        String code,

        @Schema(description = "Human-readable error message")
        String message
) {

    @Getter
    @AllArgsConstructor
    public enum Severity {
        ERROR("Error"),
        WARNING("Warning");

        @JsonValue
        private final String type;
    }

    /** Convenience factory for flow-level (no node, no edge) diagnostics. */
    public static CompileDiagnostic flowLevel(String code, String message) {
        return new CompileDiagnostic(Severity.ERROR, null, null, null, null, code, message);
    }

    /** Convenience factory for node-level diagnostics (no specific field or edge). */
    public static CompileDiagnostic nodeLevel(String nodeId, String nodeType, String code, String message) {
        return new CompileDiagnostic(Severity.ERROR, null, nodeId, nodeType, null, code, message);
    }

    /** Convenience factory for field-level diagnostics. */
    public static CompileDiagnostic fieldLevel(String nodeId, String nodeType, String field, String code, String message) {
        return new CompileDiagnostic(Severity.ERROR, null, nodeId, nodeType, field, code, message);
    }

    /** Convenience factory for edge-level diagnostics (condition edge errors). */
    public static CompileDiagnostic edgeLevel(String edgeId, String nodeId, String nodeType, String code, String message) {
        return new CompileDiagnostic(Severity.ERROR, edgeId, nodeId, nodeType, null, code, message);
    }

    /** Returns a copy of this diagnostic downgraded to {@link Severity#WARNING}. */
    public CompileDiagnostic asWarning() {
        return new CompileDiagnostic(Severity.WARNING, edgeId, nodeId, nodeType, field, code, message);
    }
}
