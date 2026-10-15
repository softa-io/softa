package io.softa.starter.metadata.seed;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.softa.starter.metadata.entity.SeedFileVersion;
import io.softa.starter.metadata.seed.SeedFileState.State;

/**
 * Compares the platform seed files of the running application with the versions the database loaded.
 * No I/O: the caller supplies the hashes and the recorded versions.
 */
public final class SeedFileStates {

    private SeedFileStates() {
    }

    /**
     * @param manifest         the application's manifest, for package names and changelogs
     * @param files            the platform files, in the order they are to be reported and loaded
     * @param checksums        file name → SHA-256 of the file in the running application
     * @param versions         every recorded version of those files
     * @param runningBuildTime build time of the running application, or null when unknown
     */
    public static List<SeedFileState> classify(SeedManifest manifest, List<SeedFile> files,
                                               Map<String, String> checksums, List<SeedFileVersion> versions,
                                               LocalDateTime runningBuildTime) {
        Map<String, List<SeedFileVersion>> byFile = versions.stream()
                .collect(Collectors.groupingBy(SeedFileVersion::getFileName));
        Map<String, SeedPackage> packages = manifest.packages().stream()
                .collect(Collectors.toMap(SeedPackage::key, p -> p));
        List<SeedFileState> out = new ArrayList<>(files.size());
        for (SeedFile file : files) {
            String checksum = checksums.get(file.file());
            List<SeedFileVersion> history = byFile.getOrDefault(file.file(), List.of());
            SeedFileVersion latest = history.stream()
                    .filter(v -> v.getSyncedTime() != null)
                    .max(Comparator.comparing(SeedFileVersion::getSyncedTime))
                    .orElse(null);
            SeedPackage pkg = packages.get(file.packageKey());
            out.add(new SeedFileState(file.file(), file.level(), file.packageKey(),
                    pkg == null ? null : pkg.name(), pkg == null ? null : pkg.changelog(),
                    stateOf(checksum, latest, history, runningBuildTime), checksum,
                    latest == null ? null : latest.getChecksum(),
                    latest == null ? null : latest.getSyncedTime(),
                    latest == null ? null : latest.getAppVersion()));
        }
        return out;
    }

    /**
     * Content the database has moved past counts as a rollback only while the running build is not newer
     * than the one that moved it: a later release may restore earlier content on purpose, and that is an
     * ordinary change to sync. With either build time unknown it counts as a rollback — refusing a
     * legitimate revert once is recoverable, overwriting newer data with an old release is not.
     */
    static State stateOf(String checksum, SeedFileVersion latest, List<SeedFileVersion> history,
                         LocalDateTime runningBuildTime) {
        if (latest == null) {
            return State.PENDING;
        }
        if (latest.getChecksum().equals(checksum)) {
            return State.SYNCED;
        }
        boolean seenBefore = history.stream()
                .anyMatch(v -> v != latest && v.getChecksum().equals(checksum));
        boolean newerBuild = runningBuildTime != null && latest.getBuildTime() != null
                && runningBuildTime.isAfter(latest.getBuildTime());
        return seenBefore && !newerBuild ? State.ROLLED_BACK : State.PENDING;
    }
}
