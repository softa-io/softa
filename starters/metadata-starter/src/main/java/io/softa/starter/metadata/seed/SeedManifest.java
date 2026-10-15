package io.softa.starter.metadata.seed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The application's seed files, as its {@code seed-manifest.yml} registers them: which files exist, who
 * their rows belong to, which package and countries they serve, and what must be loaded before what.
 *
 * <p>Built only by {@link SeedManifestReader}, which has already checked it — every file registered once,
 * every dependency naming a registered file of the same level, no cycle — so the methods here can rely on
 * that and do not re-check.
 */
public final class SeedManifest {

    private final List<SeedPackage> packages;
    private final Map<String, SeedFile> filesByName;

    SeedManifest(List<SeedPackage> packages) {
        this.packages = List.copyOf(packages);
        Map<String, SeedFile> byName = new LinkedHashMap<>();
        for (SeedPackage seedPackage : packages) {
            for (SeedFile seedFile : seedPackage.files()) {
                byName.put(seedFile.file(), seedFile);
            }
        }
        this.filesByName = byName;
    }

    /** The packages, in manifest order. */
    public List<SeedPackage> packages() {
        return packages;
    }

    /** Every registered file, in manifest order. */
    public List<SeedFile> files() {
        return List.copyOf(filesByName.values());
    }

    /** The file registered under this name, if any. */
    public Optional<SeedFile> file(String fileName) {
        return Optional.ofNullable(filesByName.get(fileName));
    }

    /** The files of one level, in manifest order. */
    public List<SeedFile> files(SeedLevel level) {
        return filesByName.values().stream().filter(seedFile -> seedFile.level() == level).toList();
    }

    /**
     * The file names of one level in the order they can be loaded: every file after the files it depends
     * on, and otherwise in manifest order — so a manifest without dependencies loads exactly as written.
     */
    /**
     * The tenant files a tenant is due, in load order: those of the packages its plan entitles — a package
     * with no module is for every plan — that are for its countries.
     *
     * @param modules   the modules the tenant's plan entitles; null when entitlement is not in use, which
     *                  makes every package due
     * @param countries the tenant's countries
     */
    public List<String> tenantFiles(Set<String> modules, Set<String> countries) {
        return loadOrder(SeedLevel.TENANT).stream()
                .map(name -> file(name).orElseThrow())
                .filter(seedFile -> isEntitled(seedFile, modules) && seedFile.appliesToCountries(countries))
                .map(SeedFile::file)
                .toList();
    }

    /** Whether the plan entitles the file's package. */
    public boolean isEntitled(SeedFile seedFile, Set<String> modules) {
        if (modules == null) {
            return true;
        }
        String module = packageOf(seedFile).module();
        return module == null || module.isBlank() || modules.contains(module);
    }

    /** The package the file is registered in. */
    public SeedPackage packageOf(SeedFile seedFile) {
        return packages.stream().filter(p -> p.key().equals(seedFile.packageKey())).findFirst().orElseThrow();
    }

    public List<String> loadOrder(SeedLevel level) {
        List<SeedFile> pending = new ArrayList<>(files(level));
        Set<String> loaded = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            SeedFile next = pending.stream()
                    .filter(seedFile -> loaded.containsAll(seedFile.dependsOn()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Seed manifest dependencies cannot be ordered; the reader should have refused it."));
            loaded.add(next.file());
            pending.remove(next);
        }
        return List.copyOf(loaded);
    }
}
