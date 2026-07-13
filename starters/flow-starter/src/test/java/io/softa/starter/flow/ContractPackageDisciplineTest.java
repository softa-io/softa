package io.softa.starter.flow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the contract-package discipline established when the former flow-api
 * module was merged into flow-starter: the packages below are the engine's
 * externally consumable contract (DTOs, enums, node configs, SPI interfaces,
 * the FlowClient port and design definitions). They must stay free of Spring
 * and servlet imports so the contract can be re-extracted into a standalone
 * artifact the moment a remote consumer that does not run the engine appears.
 */
class ContractPackageDisciplineTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/io/softa/starter/flow");

    /** Contract directories relative to the flow package root. */
    private static final List<String> CONTRACT_DIRS = List.of(
            "api",
            "design",
            "dto",
            "enums",
            "runtime/api",
            "runtime/exception",
            "runtime/nodeconfig",
            "runtime/state");

    /** Contract files living in directories shared with engine code. */
    private static final List<String> CONTRACT_FILE_GLOBS = List.of(
            "runtime/spi/*.java",
            "runtime/task/*.java");

    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "import org.springframework.",
            "import jakarta.servlet.");

    @Test
    void contractPackagesMustNotImportSpringOrServlet() throws IOException {
        StringBuilder violations = new StringBuilder();

        for (String dir : CONTRACT_DIRS) {
            try (Stream<Path> files = Files.walk(SOURCE_ROOT.resolve(dir))) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> collectViolations(p, violations));
            }
        }
        for (String glob : CONTRACT_FILE_GLOBS) {
            Path parent = SOURCE_ROOT.resolve(glob).getParent();
            String pattern = glob.substring(glob.lastIndexOf('/') + 1);
            try (var files = Files.newDirectoryStream(parent, pattern)) {
                files.forEach(p -> collectViolations(p, violations));
            }
        }

        assertTrue(violations.isEmpty(),
                "Contract packages must stay Spring/servlet-free (see class javadoc):\n" + violations);
    }

    private static void collectViolations(Path file, StringBuilder violations) {
        try {
            for (String line : Files.readAllLines(file)) {
                for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
                    if (line.startsWith(prefix)) {
                        violations.append(file).append(": ").append(line).append('\n');
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot read " + file, e);
        }
    }
}
