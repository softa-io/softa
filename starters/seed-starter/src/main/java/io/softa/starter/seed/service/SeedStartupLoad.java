package io.softa.starter.seed.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.jdbc.database.DbSessionLock;
import io.softa.framework.orm.startup.StartupConvergence;
import io.softa.framework.orm.startup.StartupSnapshots;
import io.softa.starter.seed.dto.PlatformLoadResult;
import io.softa.starter.seed.manifest.SeedFile;
import io.softa.starter.seed.manifest.SeedManifestException;
import io.softa.starter.seed.manifest.SeedManifestReader;

/**
 * Loads the seed files the build owns at startup — the platform files in mode {@code startup} — before the
 * application takes traffic and before the in-memory snapshots are built from their rows ({@link StartupSnapshots}).
 * A deploy therefore brings its navigation, permissions and the other files the build owns with it, and every
 * instance starts with snapshots that match: no load from the page, and no restart after it.
 *
 * <p>First the manifest is checked against the snapshots: every platform file writing a model a snapshot is built
 * from must be one the build owns. A file loaded from the page would change those rows while the instances run, and
 * no snapshot would see the change until they restarted — so such a manifest does not start.
 *
 * <p>Instances starting together take turns on a database lock: the first loads the files, the others find nothing
 * pending once they hold it. A failed load stops the start. In a rolling deploy the instance being replaced keeps
 * serving, and the failure is recorded against the file on the Seed Data page; the next start tries again.
 */
@Slf4j
@Component
public class SeedStartupLoad implements StartupConvergence {

    /** The lock is {@code softa:seed-startup:<database>}. */
    static final String LOCK_KEY = "seed-startup";
    /** A sibling loading the same files is done in seconds; one holding the lock this long is stuck. */
    static final int LOCK_TIMEOUT_SECONDS = 120;

    private final SeedCatalog catalog;
    private final PlatformSeedLoader platformLoader;
    private final StartupSnapshots startupSnapshots;
    /** The models a file writes, read from the file in the running build. */
    private final Function<SeedFile, Set<String>> fileModels;
    /** For the {@link DbSessionLock} only; {@code null} in the test seam ⇒ lockless. */
    private final DataSource lockDataSource;
    private final String datasourceUrl;

    @Autowired
    public SeedStartupLoad(SeedCatalog catalog, PlatformSeedLoader platformLoader, StartupSnapshots startupSnapshots,
                           JdbcTemplate jdbcTemplate, @Value("${spring.datasource.url:}") String datasourceUrl) {
        this(catalog, platformLoader, startupSnapshots, SeedFileModels::of, jdbcTemplate.getDataSource(),
                datasourceUrl);
    }

    /** Test seam: the models of each file given, and lockless without a {@link DataSource}. */
    SeedStartupLoad(SeedCatalog catalog, PlatformSeedLoader platformLoader, StartupSnapshots startupSnapshots,
                    Function<SeedFile, Set<String>> fileModels, DataSource lockDataSource, String datasourceUrl) {
        this.catalog = catalog;
        this.platformLoader = platformLoader;
        this.startupSnapshots = startupSnapshots;
        this.fileModels = fileModels;
        this.lockDataSource = lockDataSource;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    public void converge() {
        checkSnapshotModelsAreOwned();
        if (platformLoader.pendingStartupFiles().isEmpty()) {
            return;
        }
        try (DbSessionLock ignored = DbSessionLock.acquire(lockDataSource, datasourceUrl, LOCK_KEY,
                LOCK_TIMEOUT_SECONDS)) {
            // Asked again under the lock: an instance that started alongside may have loaded them meanwhile.
            platformLoader.loadStartupFiles().ifPresent(SeedStartupLoad::requireLoaded);
        }
    }

    private static void requireLoaded(PlatformLoadResult result) {
        if (!result.succeeded()) {
            throw new IllegalStateException("The seed files the build owns did not load ("
                    + (result.failedFile() == null ? "before any file" : result.failedFile()) + "): "
                    + result.errorMessage() + ". The application does not start on rows that do not match its "
                    + "build; the failure is recorded against the file, and the next start tries again.");
        }
        log.info("Seed startup load {}: {} loaded", result.batchId(), result.loadedFiles());
    }

    /**
     * Refuse a manifest in which a file the page loads writes a model a startup snapshot is built from: that load
     * would reach no snapshot until every instance restarted.
     */
    private void checkSnapshotModelsAreOwned() {
        Set<String> snapshotModels = startupSnapshots.sourceModels();
        if (snapshotModels.isEmpty()) {
            return;
        }
        List<String> problems = new ArrayList<>();
        for (SeedFile file : catalog.manifest().platformFiles()) {
            if (file.loadsAtStartup()) {
                continue;
            }
            Set<String> written = new TreeSet<>(fileModels.apply(file));
            written.retainAll(snapshotModels);
            if (!written.isEmpty()) {
                problems.add("file '" + file.path() + "' writes " + String.join(", ", written) + ", which the "
                        + "in-memory snapshots are built from at startup: give it 'mode: startup', so it loads at "
                        + "startup and nowhere else");
            }
        }
        if (!problems.isEmpty()) {
            throw new SeedManifestException(SeedManifestReader.DEFAULT_LOCATION, problems);
        }
    }
}
