package fixtures.tplvars.invalid;

import java.util.List;
import java.util.Map;

import io.softa.framework.base.annotation.TemplateVariables;

/**
 * Declarations the registry refuses, each for one reason. Kept outside {@code io.softa}, which
 * every application scans.
 */
public final class Invalid {

    private Invalid() {
    }

    @TemplateVariables(name = "test.not-a-record")
    public static class NotARecord {
    }

    @TemplateVariables(name = "test.bad-type")
    public record BadType(Map<String, Object> anything) {
    }

    @TemplateVariables(name = "test.bad-list")
    public record BadList(List<String> names) {
    }

    public record Node(String name, Node parent) {
    }

    @TemplateVariables(name = "test.cycle")
    public record Cycle(Node node) {
    }
}
