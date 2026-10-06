package io.softa.starter.metadata.seed;

import java.util.List;

/**
 * A seed manifest that cannot be used, with everything wrong with it.
 */
public class SeedManifestException extends IllegalStateException {

    private final transient List<String> problems;

    public SeedManifestException(String source, List<String> problems) {
        super("Invalid seed manifest " + source + ":\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    /** Each problem found, one sentence each. */
    public List<String> getProblems() {
        return problems;
    }
}
