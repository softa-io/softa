package io.softa.starter.metadata.seed;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads and checks a {@code seed-manifest.yml}.
 *
 * <p>The manifest is a list of packages:
 * <pre>{@code
 * - key: leave
 *   name: Leave
 *   module: leave                     # plan module; omit for a package every tenant gets
 *   changelog: Carry-forward expiry moved to 30 June
 *   files:
 *     - file: LeaveType.SG.json
 *       level: TENANT                 # TENANT | PLATFORM_GLOBAL | PLATFORM_TENANT
 *       countries: [SG]               # omit for every country
 *     - file: LeavePolicy.SG.json
 *       level: TENANT
 *       countries: [SG]
 *       dependsOn: [LeaveType.SG.json]
 *       push: None                    # None | InvalidColumns | NewNestedItems
 *       retired: [leave_policy.SG.Old] # preIds this file must never use again
 * }</pre>
 *
 * <p>Everything wrong with a manifest is collected and reported at once, so a broken manifest is fixed in
 * one pass: unknown keys (a misspelt {@code dependOn} would otherwise be silently ignored), a missing or
 * unknown level or push scope, a file registered twice, a dependency on an unregistered file or on a file
 * of another level (the levels are loaded separately, so the order between them cannot be promised), a
 * dependency cycle, and blank or repeated entries in a list.
 */
public final class SeedManifestReader {

    /** Where an application keeps its manifest, relative to the classpath root. */
    public static final String DEFAULT_LOCATION = "seed-manifest.yml";

    private static final Set<String> PACKAGE_KEYS = Set.of("key", "name", "module", "changelog", "files");
    private static final Set<String> FILE_KEYS =
            Set.of("file", "level", "countries", "dependsOn", "push", "retired");

    private SeedManifestReader() {
    }

    /** Read the manifest at {@link #DEFAULT_LOCATION} on the classpath. */
    public static SeedManifest readClasspath() {
        return readClasspath(DEFAULT_LOCATION);
    }

    /** Read the manifest at a classpath location. */
    public static SeedManifest readClasspath(String location) {
        ClassPathResource resource = new ClassPathResource(location);
        if (!resource.exists()) {
            throw new SeedManifestException(location, List.of("the manifest does not exist on the classpath"));
        }
        try (InputStream in = resource.getInputStream()) {
            return read(in, location);
        } catch (IOException e) {
            throw new SeedManifestException(location, List.of("the manifest cannot be read: " + e.getMessage()));
        }
    }

    /**
     * Read a manifest from a stream.
     *
     * @param in     the manifest's YAML
     * @param source where it came from, named in error messages
     * @return the checked manifest
     * @throws SeedManifestException listing everything wrong with it
     */
    public static SeedManifest read(InputStream in, String source) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (RuntimeException e) {
            throw new SeedManifestException(source, List.of("the manifest is not valid YAML: " + e.getMessage()));
        }
        List<String> problems = new ArrayList<>();
        List<SeedPackage> packages = parsePackages(root, problems);
        checkReferences(packages, problems);
        if (!problems.isEmpty()) {
            throw new SeedManifestException(source, problems);
        }
        return new SeedManifest(packages);
    }

    private static List<SeedPackage> parsePackages(Object root, List<String> problems) {
        if (root == null) {
            return List.of();
        }
        if (!(root instanceof List<?> items)) {
            problems.add("the manifest must be a list of packages");
            return List.of();
        }
        List<SeedPackage> packages = new ArrayList<>();
        Set<String> packageKeys = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String where = "package #" + (i + 1);
            if (!(items.get(i) instanceof Map<?, ?> map)) {
                problems.add(where + " must be a map");
                continue;
            }
            String key = text(map, "key");
            if (key == null) {
                problems.add(where + " has no key");
                key = where;
            } else {
                where = "package '" + key + "'";
                if (!packageKeys.add(key)) {
                    problems.add(where + " is registered twice");
                }
            }
            unknownKeys(map, PACKAGE_KEYS, where, problems);
            List<SeedFile> files = parseFiles(map.get("files"), key, where, problems);
            packages.add(new SeedPackage(key, text(map, "name"), text(map, "module"), text(map, "changelog"), files));
        }
        return packages;
    }

    private static List<SeedFile> parseFiles(Object value, String packageKey, String where, List<String> problems) {
        if (value == null) {
            problems.add(where + " has no files");
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            problems.add(where + ": files must be a list");
            return List.of();
        }
        List<SeedFile> files = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String fileWhere = where + ", file #" + (i + 1);
            if (!(items.get(i) instanceof Map<?, ?> map)) {
                problems.add(fileWhere + " must be a map");
                continue;
            }
            String file = text(map, "file");
            if (file == null) {
                problems.add(fileWhere + " has no file name");
                continue;
            }
            fileWhere = "file '" + file + "'";
            unknownKeys(map, FILE_KEYS, fileWhere, problems);
            SeedLevel level = parseLevel(map.get("level"), fileWhere, problems);
            SeedPushScope push = parsePush(map.get("push"), fileWhere, problems);
            List<String> countries = textList(map, "countries", fileWhere, problems);
            List<String> dependsOn = textList(map, "dependsOn", fileWhere, problems);
            List<String> retired = textList(map, "retired", fileWhere, problems);
            if (level != null) {
                files.add(new SeedFile(file, level, countries, dependsOn, push, retired, packageKey));
            }
        }
        return files;
    }

    private static SeedLevel parseLevel(Object value, String where, List<String> problems) {
        if (value == null) {
            problems.add(where + " has no level (TENANT, PLATFORM_GLOBAL or PLATFORM_TENANT)");
            return null;
        }
        try {
            return SeedLevel.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            problems.add(where + " has an unknown level '" + value
                    + "' (TENANT, PLATFORM_GLOBAL or PLATFORM_TENANT)");
            return null;
        }
    }

    private static SeedPushScope parsePush(Object value, String where, List<String> problems) {
        if (value == null) {
            return SeedPushScope.NONE;
        }
        return SeedPushScope.ofCode(value.toString()).orElseGet(() -> {
            problems.add(where + " has an unknown push '" + value + "' (None, InvalidColumns or NewNestedItems)");
            return SeedPushScope.NONE;
        });
    }

    /** Every file registered once; every dependency on a registered file of the same level; no cycle. */
    private static void checkReferences(List<SeedPackage> packages, List<String> problems) {
        Map<String, SeedFile> byName = new LinkedHashMap<>();
        for (SeedPackage seedPackage : packages) {
            for (SeedFile seedFile : seedPackage.files()) {
                SeedFile earlier = byName.putIfAbsent(seedFile.file(), seedFile);
                if (earlier != null) {
                    problems.add("file '" + seedFile.file() + "' is registered twice (packages '"
                            + earlier.packageKey() + "' and '" + seedFile.packageKey() + "')");
                }
            }
        }
        for (SeedFile seedFile : byName.values()) {
            for (String dependency : seedFile.dependsOn()) {
                SeedFile target = byName.get(dependency);
                if (dependency.equals(seedFile.file())) {
                    problems.add("file '" + seedFile.file() + "' depends on itself");
                } else if (target == null) {
                    problems.add("file '" + seedFile.file() + "' depends on '" + dependency
                            + "', which is not registered");
                } else if (target.level() != seedFile.level()) {
                    problems.add("file '" + seedFile.file() + "' (" + seedFile.level() + ") depends on '"
                            + dependency + "' (" + target.level() + "); a file can only depend on files of "
                            + "its own level, which are loaded together");
                }
            }
        }
        findCycle(byName).ifPresent(cycle -> problems.add("files depend on each other in a cycle: "
                + String.join(" -> ", cycle)));
    }

    private static Optional<List<String>> findCycle(Map<String, SeedFile> byName) {
        Map<String, Integer> state = new HashMap<>();   // absent = unvisited, 1 = on the path, 2 = done
        for (String start : byName.keySet()) {
            List<String> cycle = visit(start, byName, state, new ArrayList<>());
            if (cycle != null) {
                return Optional.of(cycle);
            }
        }
        return Optional.empty();
    }

    private static List<String> visit(String file, Map<String, SeedFile> byName, Map<String, Integer> state,
                                      List<String> path) {
        Integer seen = state.get(file);
        if (seen != null && seen == 2) {
            return null;
        }
        if (seen != null) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(file), path.size()));
            cycle.add(file);
            return cycle;
        }
        state.put(file, 1);
        path.add(file);
        SeedFile seedFile = byName.get(file);
        if (seedFile != null) {
            for (String dependency : seedFile.dependsOn()) {
                if (byName.containsKey(dependency) && !dependency.equals(file)) {
                    List<String> cycle = visit(dependency, byName, state, path);
                    if (cycle != null) {
                        return cycle;
                    }
                }
            }
        }
        path.removeLast();
        state.put(file, 2);
        return null;
    }

    private static void unknownKeys(Map<?, ?> map, Set<String> allowed, String where, List<String> problems) {
        for (Object key : map.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                problems.add(where + " has an unknown key '" + key + "' (allowed: " + sorted(allowed) + ")");
            }
        }
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** A list of non-blank, distinct strings; a missing key is an empty list. */
    private static List<String> textList(Map<?, ?> map, String key, String where, List<String> problems) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            problems.add(where + ": " + key + " must be a list");
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : items) {
            String text = item == null ? "" : item.toString().trim();
            if (text.isEmpty()) {
                problems.add(where + ": " + key + " has a blank entry");
            } else if (!seen.add(text)) {
                problems.add(where + ": " + key + " lists '" + text + "' twice");
            } else {
                values.add(text);
            }
        }
        return values;
    }

    private static String sorted(Collection<String> values) {
        return String.join(", ", values.stream().sorted().toList());
    }
}
