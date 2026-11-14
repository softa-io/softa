package io.softa.starter.seed.manifest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads and checks a {@code seed-manifest.yml}.
 *
 * <p>Two lists, each in load order:
 * <pre>{@code
 * platform:                                   # loaded whole whenever they change
 *   - file: data-system/Navigation.json       # shared rows
 *     mode: startup                           # the build owns it: loads at startup, wherever pending
 *   - data-platform/MailTemplate.Platform.json  # the platform tenant's own rows, loaded from the page
 * tenant:                                     # run once in each tenant, unless the file's mode says otherwise
 *   - files:                                  # every tenant
 *       - data-tenant/SysSequence.json
 *       - file: data-tenant/MailTemplate.json
 *         mode: append                        # run again when it changes, creating the rows a tenant lacks
 *       - file: data-tenant/Role.Tenant.json
 *         mode: overwrite                     # run again, whole, whenever the file changes
 *   - module: leave                           # tenants whose plan includes the module
 *     countries: [NZ]                         # and that work in one of these countries
 *     files:
 *       - data-tenant/LeaveType.NZ.json
 * }</pre>
 *
 * <p>A file's directory says whose rows it holds, so a platform file sits in {@code data-system/} or
 * {@code data-platform/} and a tenant file in {@code data-tenant/}. Its {@code mode} says what it does after a scope
 * has loaded it ({@link SeedFileMode}): a platform file is {@code overwrite} (the default) or {@code startup}, a
 * tenant file {@code once} (the default), {@code append} or {@code overwrite}. Everything wrong is collected and
 * reported at once, so a broken manifest is fixed in one pass: an unknown key (a misspelt {@code countrys} would
 * otherwise be silently ignored; the {@code auto} and {@code overwrite} flags the mode replaced are named with what
 * to write instead), a file in a directory its list does not take, a mode its list does not take, a file registered
 * twice or missing from the classpath, a file that is not JSON or CSV, a {@code module} that is not one name, a
 * country that is not a two-letter code, and blank or repeated entries. A key written twice is refused before any
 * of that: YAML would keep the second and drop the first, with its files.
 */
public final class SeedManifestReader {

    /** Where an application keeps its manifest, relative to the classpath root. */
    public static final String DEFAULT_LOCATION = "seed-manifest.yml";

    private static final String PLATFORM = "platform";
    private static final String TENANT = "tenant";
    private static final String FILE = "file";
    private static final String MODE = "mode";
    private static final String MODULE = "module";
    private static final String COUNTRIES = "countries";
    private static final String FILES = "files";

    private static final Set<String> ROOT_KEYS = Set.of(PLATFORM, TENANT);
    private static final Set<String> GROUP_KEYS = Set.of(MODULE, COUNTRIES, FILES);
    private static final Set<String> FILE_KEYS = Set.of(FILE, MODE);
    /** The flags the mode replaced, and what to write instead — named where one is still found. */
    private static final Map<String, String> REPLACED_KEYS = Map.of(
            "auto", "'auto: true' is now 'mode: startup'",
            "overwrite", "'overwrite: true' is now 'mode: overwrite'");
    /** The formats the loader reads. */
    private static final List<String> FILE_EXTENSIONS = List.of(".json", ".csv");
    /** ISO 3166-1 alpha-2, as {@code CountryRegion} ids are. */
    private static final Pattern COUNTRY_CODE = Pattern.compile("[A-Z]{2}");

    private SeedManifestReader() {
    }

    /** Read the manifest at {@link #DEFAULT_LOCATION}, checking every file exists on the classpath. */
    public static SeedManifest readClasspath() {
        ClassPathResource resource = new ClassPathResource(DEFAULT_LOCATION);
        if (!resource.exists()) {
            throw new SeedManifestException(DEFAULT_LOCATION, List.of("the manifest does not exist on the classpath; "
                    + "seed-starter needs one listing every seed file the application ships"));
        }
        try (InputStream in = resource.getInputStream()) {
            return read(in, DEFAULT_LOCATION, path -> new ClassPathResource(path).exists());
        } catch (IOException e) {
            throw new SeedManifestException(DEFAULT_LOCATION, List.of("the manifest cannot be read: " + e.getMessage()));
        }
    }

    /**
     * Read a manifest from a stream.
     *
     * @param in         the manifest's YAML
     * @param source     where it came from, named in error messages
     * @param fileExists whether a classpath location exists, e.g. {@code data-tenant/LeaveType.NZ.json}
     * @return the checked manifest
     * @throws SeedManifestException listing everything wrong with it
     */
    public static SeedManifest read(InputStream in, String source, Predicate<String> fileExists) {
        Object root;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            root = new Yaml(new SafeConstructor(options)).load(in);
        } catch (DuplicateKeyException e) {
            throw new SeedManifestException(source, List.of("the manifest repeats a key, and YAML would keep only "
                    + "the second, dropping the first one's files: " + e.getProblem()));
        } catch (RuntimeException e) {
            throw new SeedManifestException(source, List.of("the manifest is not valid YAML: " + e.getMessage()));
        }
        List<String> problems = new ArrayList<>();
        List<SeedFile> platformFiles = new ArrayList<>();
        List<SeedFile> tenantFiles = new ArrayList<>();
        if (root instanceof Map<?, ?> map) {
            unknownKeys(map, ROOT_KEYS, "the manifest", problems);
            platformFiles = parsePlatform(map.get(PLATFORM), problems);
            tenantFiles = parseTenant(map.get(TENANT), problems);
        } else if (root != null) {
            problems.add("the manifest must be a map with a `platform` list and a `tenant` list");
        }
        List<SeedFile> files = new ArrayList<>(platformFiles);
        files.addAll(tenantFiles);
        checkFiles(files, fileExists, problems);
        if (!problems.isEmpty()) {
            throw new SeedManifestException(source, problems);
        }
        return new SeedManifest(platformFiles, tenantFiles);
    }

    /** The platform list: files only, each in a platform directory. */
    private static List<SeedFile> parsePlatform(Object value, List<String> problems) {
        List<SeedFile> files = new ArrayList<>();
        for (Entry entry : entries(value, "platform", problems)) {
            SeedFileMode mode = mode(entry, true, problems);
            Optional<SeedLevel> level = SeedLevel.ofPath(entry.path()).filter(SeedLevel::isPlatform);
            if (level.isEmpty()) {
                problems.add("platform file '" + entry.path() + "' is not in data-system/ or data-platform/");
                continue;
            }
            fileName(entry.path(), level.get(), problems).ifPresent(name ->
                    files.add(new SeedFile(name, level.get(), mode, null, List.of())));
        }
        return files;
    }

    /** The tenant list: groups of files, each group with the tenants it is for. */
    private static List<SeedFile> parseTenant(Object value, List<String> problems) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> groups)) {
            problems.add("tenant must be a list of groups, each with its files");
            return List.of();
        }
        List<SeedFile> files = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            String where = "tenant group #" + (i + 1);
            if (!(groups.get(i) instanceof Map<?, ?> group)) {
                problems.add(where + " must be a map with its files");
                continue;
            }
            unknownKeys(group, GROUP_KEYS, where, problems);
            String module = module(group, where, problems);
            List<String> countries = countries(group, where, problems);
            if (group.get(FILES) == null) {
                problems.add(where + " has no files");
                continue;
            }
            for (Entry entry : entries(group.get(FILES), where + " files", problems)) {
                SeedFileMode mode = mode(entry, false, problems);
                if (SeedLevel.ofPath(entry.path()).orElse(null) != SeedLevel.TENANT) {
                    problems.add("tenant file '" + entry.path() + "' is not in data-tenant/");
                    continue;
                }
                fileName(entry.path(), SeedLevel.TENANT, problems).ifPresent(name ->
                        files.add(new SeedFile(name, SeedLevel.TENANT, mode, module, countries)));
            }
        }
        return files;
    }

    /** One file entry: its path, and its mode as written — null when it names none. */
    private record Entry(String path, Object mode) {
    }

    /**
     * The entries of a list of files. An entry is the file's path, or a map with the path under {@code file} and
     * the file's {@code mode}.
     */
    private static List<Entry> entries(Object value, String where, List<String> problems) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            problems.add(where + " must be a list of files");
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String entryWhere = where + " #" + (i + 1);
            Object item = items.get(i);
            if (item instanceof String path && !path.isBlank()) {
                entries.add(new Entry(path.trim(), null));
            } else if (item instanceof Map<?, ?> map) {
                String path = text(map, FILE);
                if (path == null) {
                    problems.add(entryWhere + " has no file");
                    continue;
                }
                unknownKeys(map, FILE_KEYS, "file '" + path + "'", problems);
                entries.add(new Entry(path, map.get(MODE)));
            } else {
                problems.add(entryWhere + " must be a file path, or a map with the path under `file`");
            }
        }
        return entries;
    }

    /**
     * An entry's mode: its list's default when it names none, else one of the modes its list takes, as the manifest
     * writes it. A bare {@code on} or {@code yes}, which YAML reads as a boolean, names no mode.
     *
     * @param platform whether the entry is in the platform list
     */
    private static SeedFileMode mode(Entry entry, boolean platform, List<String> problems) {
        List<SeedFileMode> modes = platform ? SeedFileMode.PLATFORM_MODES : SeedFileMode.TENANT_MODES;
        if (entry.mode() == null) {
            return modes.getFirst();
        }
        Optional<SeedFileMode> mode = entry.mode() instanceof String code
                ? SeedFileMode.of(code.trim()) : Optional.empty();
        if (mode.isPresent() && modes.contains(mode.get())) {
            return mode.get();
        }
        String list = platform ? "platform" : "tenant";
        problems.add(list + " file '" + entry.path() + "' has mode '" + entry.mode() + "'"
                + (mode.isPresent() ? ", which only a " + (platform ? "tenant" : "platform") + " file takes" : "")
                + "; a " + list + " file's mode is " + described(modes));
        return modes.getFirst();
    }

    /** The modes as the manifest writes them, the default first: "once (the default), append or overwrite". */
    private static String described(List<SeedFileMode> modes) {
        List<String> codes = new ArrayList<>(modes.stream().map(SeedFileMode::getCode).toList());
        codes.set(0, codes.getFirst() + " (the default)");
        return String.join(", ", codes.subList(0, codes.size() - 1)) + " or " + codes.getLast();
    }

    /** The file's name inside its level's directory — what the load records name it by. */
    private static Optional<String> fileName(String path, SeedLevel level, List<String> problems) {
        String name = path.substring(level.dataDir().length());
        if (name.isBlank() || name.contains("/")) {
            problems.add("file '" + path + "' must sit directly in " + level.dataDir());
            return Optional.empty();
        }
        if (FILE_EXTENSIONS.stream().noneMatch(name::endsWith)) {
            problems.add("file '" + path + "' is not a " + String.join(" or ", FILE_EXTENSIONS)
                    + " file, the formats the loader reads");
            return Optional.empty();
        }
        return Optional.of(name);
    }

    /** Every file registered once, and present on the classpath. */
    private static void checkFiles(List<SeedFile> files, Predicate<String> fileExists, List<String> problems) {
        Map<String, SeedFile> byName = new LinkedHashMap<>();
        for (SeedFile seedFile : files) {
            SeedFile earlier = byName.putIfAbsent(seedFile.file(), seedFile);
            if (earlier != null) {
                problems.add("file '" + seedFile.file() + "' is registered twice"
                        + (earlier.path().equals(seedFile.path()) ? "" : " (" + earlier.path() + " and "
                        + seedFile.path() + "); the load records name a file by its name alone"));
            } else if (!fileExists.test(seedFile.path())) {
                problems.add("file '" + seedFile.path() + "' is not on the classpath");
            }
        }
    }

    private static void unknownKeys(Map<?, ?> map, Set<String> allowed, String where, List<String> problems) {
        for (Object key : map.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                String replaced = REPLACED_KEYS.get(String.valueOf(key));
                problems.add(where + " has an unknown key '" + key + "' (allowed: " + sorted(allowed) + ")"
                        + (replaced == null ? "" : "; " + replaced));
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

    /**
     * A group's module: one name, or none. A group is for one module — a list would otherwise read as one
     * strange name that no tenant's plan includes.
     */
    private static String module(Map<?, ?> group, String where, List<String> problems) {
        Object value = group.get(MODULE);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String name) || name.isBlank()) {
            problems.add(where + ": module must be one module name, not '" + value
                    + "'; give each module a group of its own");
            return null;
        }
        return name.trim();
    }

    /**
     * A group's countries: distinct two-letter codes; a missing key is none. A code is checked as written,
     * because YAML reads an unquoted {@code NO} (Norway) as false, and a lower-case code never matches.
     */
    private static List<String> countries(Map<?, ?> group, String where, List<String> problems) {
        Object value = group.get(COUNTRIES);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            problems.add(where + ": " + COUNTRIES + " must be a list");
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Boolean) {
                problems.add(where + ": " + COUNTRIES + " has '" + item + "', which YAML reads from an unquoted "
                        + "NO / YES / ON / OFF; quote the code, as in \"NO\"");
            } else if (!(item instanceof String code) || !COUNTRY_CODE.matcher(code).matches()) {
                problems.add(where + ": " + COUNTRIES + " has '" + item + "', which is not a two-letter upper-case "
                        + "country code");
            } else if (codes.contains(code)) {
                problems.add(where + ": " + COUNTRIES + " lists '" + code + "' twice");
            } else {
                codes.add(code);
            }
        }
        return codes;
    }

    private static String sorted(Collection<String> values) {
        return String.join(", ", values.stream().sorted().toList());
    }
}
