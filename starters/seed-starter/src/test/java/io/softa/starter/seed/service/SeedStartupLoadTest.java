package io.softa.starter.seed.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.startup.StartupSnapshots;
import io.softa.starter.seed.dto.PlatformLoadResult;
import io.softa.starter.seed.manifest.SeedFile;
import io.softa.starter.seed.manifest.SeedManifest;
import io.softa.starter.seed.manifest.SeedManifestException;
import io.softa.starter.seed.manifest.SeedManifestReader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The files the build owns load at startup, before the snapshots are built from them: a failure stops the start, and
 * a manifest that leaves a snapshot's rows to the page does not start at all.
 */
class SeedStartupLoadTest {

    private static final SeedManifest MANIFEST = SeedManifestReader.read(new ByteArrayInputStream("""
            platform:
              - file: data-system/Navigation.json
                mode: startup
              - data-system/Currency.json
            """.getBytes(StandardCharsets.UTF_8)), "test", path -> true);

    private final SeedCatalog catalog = mock(SeedCatalog.class);
    private final PlatformSeedLoader platformLoader = mock(PlatformSeedLoader.class);
    private final StartupSnapshots startupSnapshots = mock(StartupSnapshots.class);
    /** File → the models it writes; every test starts with the ones a well-formed manifest has. */
    private Map<String, Set<String>> models;

    @BeforeEach
    void setUp() {
        when(catalog.manifest()).thenReturn(MANIFEST);
        when(startupSnapshots.sourceModels()).thenReturn(Set.of("Navigation", "Permission"));
        models = Map.of("Navigation.json", Set.of("Navigation", "Permission"), "Currency.json", Set.of("Currency"));
    }

    @Test
    void nothingPendingLoadsNothing() {
        when(platformLoader.pendingStartupFiles()).thenReturn(List.of());

        startupLoad().converge();

        verify(platformLoader, never()).loadStartupFiles();
    }

    @Test
    void thePendingFilesLoad() {
        when(platformLoader.pendingStartupFiles()).thenReturn(List.of(file("Navigation.json")));
        when(platformLoader.loadStartupFiles()).thenReturn(Optional.of(
                new PlatformLoadResult(42L, true, List.of("Navigation.json"), null, null)));

        assertDoesNotThrow(() -> startupLoad().converge());
        verify(platformLoader).loadStartupFiles();
    }

    @Test
    void anInstanceThatStartedAlongsideLoadedThemMeanwhile() {
        // Pending when asked without the lock, loaded by the other instance by the time this one holds it.
        when(platformLoader.pendingStartupFiles()).thenReturn(List.of(file("Navigation.json")));
        when(platformLoader.loadStartupFiles()).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> startupLoad().converge());
    }

    @Test
    void aFailedLoadStopsTheStart() {
        when(platformLoader.pendingStartupFiles()).thenReturn(List.of(file("Navigation.json")));
        when(platformLoader.loadStartupFiles()).thenReturn(Optional.of(
                new PlatformLoadResult(42L, false, List.of(), "Navigation.json", "Duplicate entry")));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> startupLoad().converge());

        assertTrue(failure.getMessage().contains("Navigation.json"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Duplicate entry"), failure.getMessage());
    }

    @Test
    void aFileThePageLoadsMayNotWriteASnapshotsRows() {
        models = Map.of("Navigation.json", Set.of("Navigation"), "Currency.json", Set.of("Currency", "Permission"));

        SeedManifestException failure = assertThrows(SeedManifestException.class, () -> startupLoad().converge());

        assertEquals(1, failure.getProblems().size());
        assertTrue(failure.getProblems().getFirst().contains("data-system/Currency.json"), failure.getMessage());
        assertTrue(failure.getProblems().getFirst().contains("writes Permission"), failure.getMessage());
        verify(platformLoader, never()).loadStartupFiles();
    }

    private SeedStartupLoad startupLoad() {
        return new SeedStartupLoad(catalog, platformLoader, startupSnapshots, file -> models.get(file.file()),
                null, null);
    }

    private static SeedFile file(String name) {
        return MANIFEST.file(name).orElseThrow();
    }
}
