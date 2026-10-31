package io.softa.starter.seed.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The application's seed files, as its {@code seed-manifest.yml} registers them: the platform files in the
 * order a platform load takes them, and the tenant files in the order each tenant runs them. A file that
 * refers to another file's rows is registered after it.
 *
 * <p>Built only by {@link SeedManifestReader}, which has already checked it, so nothing here re-checks.
 */
public final class SeedManifest {

    private final List<SeedFile> platformFiles;
    private final List<SeedFile> tenantFiles;
    private final Map<String, SeedFile> filesByName = new LinkedHashMap<>();

    SeedManifest(List<SeedFile> platformFiles, List<SeedFile> tenantFiles) {
        this.platformFiles = List.copyOf(platformFiles);
        this.tenantFiles = List.copyOf(tenantFiles);
        files().forEach(file -> filesByName.put(file.file(), file));
    }

    /** The platform files, in load order: shared rows and the platform tenant's own, as listed. */
    public List<SeedFile> platformFiles() {
        return platformFiles;
    }

    /** The platform files the build owns, which load at startup, in load order. */
    public List<SeedFile> startupFiles() {
        return platformFiles.stream().filter(SeedFile::loadsAtStartup).toList();
    }

    /** Every tenant file, in run order. */
    public List<SeedFile> tenantFiles() {
        return tenantFiles;
    }

    /** Every file, the platform's first. */
    public List<SeedFile> files() {
        List<SeedFile> files = new ArrayList<>(platformFiles);
        files.addAll(tenantFiles);
        return files;
    }

    /** The file registered under this name, if any. */
    public Optional<SeedFile> file(String fileName) {
        return Optional.ofNullable(filesByName.get(fileName));
    }

    /**
     * The tenant files a tenant is due, in run order: those for a module its plan entitles — a file with no
     * module is for every plan — and for one of its countries.
     *
     * @param modules   the modules the tenant's plan entitles; null when plans are not in use, which makes
     *                  every file due
     * @param countries the tenant's countries
     */
    public List<SeedFile> tenantFiles(Set<String> modules, Set<String> countries) {
        return tenantFiles.stream()
                .filter(file -> file.appliesToModules(modules) && file.appliesToCountries(countries))
                .toList();
    }
}
