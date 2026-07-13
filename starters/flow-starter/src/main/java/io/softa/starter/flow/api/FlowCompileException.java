package io.softa.starter.flow.api;

import java.util.List;
import lombok.Getter;

/**
 * Raised when a design-time flow document cannot be compiled.
 *
 * <p>Carries a structured list of {@link CompileDiagnostic} entries so the
 * caller (and the REST layer) can report all errors at once and map each one
 * to the exact node and field that failed.</p>
 */
@Getter
public class FlowCompileException extends RuntimeException {

    private final List<CompileDiagnostic> diagnostics;

    public FlowCompileException(List<CompileDiagnostic> diagnostics) {
        super(diagnostics.size() + " compilation error(s)");
        this.diagnostics = List.copyOf(diagnostics);
    }

    /** Single-diagnostic constructor for convenience (retains fail-fast callers). */
    public FlowCompileException(CompileDiagnostic diagnostic) {
        this(List.of(diagnostic));
    }

    /**
     * Plain-message constructor for structural errors that originate outside the
     * validator pipeline (e.g. {@code GraphIndexBuilder}, {@code TopologicalSorter}).
     * Wraps the message as a single flow-level diagnostic with code {@code "STRUCTURAL_ERROR"}.
     */
    public FlowCompileException(String message) {
        this(CompileDiagnostic.flowLevel("STRUCTURAL_ERROR", message));
    }

}
