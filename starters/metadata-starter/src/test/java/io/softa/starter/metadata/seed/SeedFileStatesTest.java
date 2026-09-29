package io.softa.starter.metadata.seed;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.metadata.entity.SeedFileVersion;
import io.softa.starter.metadata.seed.SeedFileState.State;

import static org.assertj.core.api.Assertions.assertThat;

class SeedFileStatesTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 9, 1, 10, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 9, 15, 10, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 9, 29, 10, 0);

    private static final SeedManifest MANIFEST = SeedManifestReader.read(new ByteArrayInputStream("""
            - key: access
              name: Access catalogue
              changelog: Adds the report pages
              files:
                - file: Permission.App.json
                  level: PLATFORM_GLOBAL
            """.getBytes(StandardCharsets.UTF_8)), "test.yml");

    private static final SeedFile FILE = MANIFEST.file("Permission.App.json").orElseThrow();

    private static SeedFileVersion version(String checksum, LocalDateTime syncedTime, LocalDateTime buildTime) {
        SeedFileVersion v = new SeedFileVersion();
        v.setFileName(FILE.file());
        v.setChecksum(checksum);
        v.setSyncedTime(syncedTime);
        v.setBuildTime(buildTime);
        v.setAppVersion("build-" + checksum);
        return v;
    }

    private static SeedFileState classify(String running, LocalDateTime runningBuild, SeedFileVersion... history) {
        return SeedFileStates.classify(MANIFEST, List.of(FILE), Map.of(FILE.file(), running), List.of(history),
                runningBuild).getFirst();
    }

    @Test
    void aFileNeverLoadedIsPending() {
        SeedFileState state = classify("aaa", T1);

        assertThat(state.state()).isEqualTo(State.PENDING);
        assertThat(state.syncedChecksum()).isNull();
        assertThat(state.packageName()).isEqualTo("Access catalogue");
        assertThat(state.changelog()).isEqualTo("Adds the report pages");
    }

    @Test
    void contentEqualToTheLatestVersionIsSynced() {
        SeedFileState state = classify("bbb", T2, version("aaa", T1, T1), version("bbb", T2, T2));

        assertThat(state.state()).isEqualTo(State.SYNCED);
        assertThat(state.syncedTime()).isEqualTo(T2);
        assertThat(state.syncedAppVersion()).isEqualTo("build-bbb");
    }

    @Test
    void newContentIsPending() {
        assertThat(classify("ccc", T3, version("aaa", T1, T1), version("bbb", T2, T2)).state())
                .isEqualTo(State.PENDING);
    }

    @Test
    void anEarlierVersionUnderAnOlderBuildIsARollback() {
        assertThat(classify("aaa", T1, version("aaa", T1, T1), version("bbb", T2, T2)).state())
                .isEqualTo(State.ROLLED_BACK);
    }

    @Test
    void anEarlierVersionRestoredByANewerBuildIsPending() {
        assertThat(classify("aaa", T3, version("aaa", T1, T1), version("bbb", T2, T2)).state())
                .isEqualTo(State.PENDING);
    }

    @Test
    void anEarlierVersionWithTheBuildTimeUnknownIsARollback() {
        assertThat(classify("aaa", null, version("aaa", T1, T1), version("bbb", T2, T2)).state())
                .isEqualTo(State.ROLLED_BACK);
        assertThat(classify("aaa", T3, version("aaa", T1, null), version("bbb", T2, null)).state())
                .isEqualTo(State.ROLLED_BACK);
    }

    @Test
    void theLatestVersionIsTheLastSyncedNotTheLastBuilt() {
        // bbb was restored after ccc by a newer release: bbb is current, ccc is the past.
        SeedFileState state = classify("bbb", T3, version("ccc", T2, T2), version("bbb", T3, T3));

        assertThat(state.state()).isEqualTo(State.SYNCED);
    }
}
