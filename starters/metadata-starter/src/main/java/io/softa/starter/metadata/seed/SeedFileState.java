package io.softa.starter.metadata.seed;

import java.time.LocalDateTime;

/**
 * Where one platform seed file stands: its content in the running application against what the
 * database last loaded.
 *
 * @param file             file name
 * @param level            {@code PLATFORM_GLOBAL} or {@code PLATFORM_TENANT}
 * @param packageKey       key of the manifest package the file is registered in
 * @param packageName      display name of that package
 * @param changelog        changelog of that package
 * @param state            how the running content compares to the loaded one
 * @param checksum         SHA-256 of the file in the running application
 * @param syncedChecksum   SHA-256 of the version last loaded, or null when none was
 * @param syncedTime       when that version was loaded
 * @param syncedAppVersion version of the application that loaded it
 */
public record SeedFileState(String file, SeedLevel level, String packageKey, String packageName, String changelog,
                            State state, String checksum, String syncedChecksum, LocalDateTime syncedTime,
                            String syncedAppVersion) {

    public enum State {
        /** Never loaded into this database, or changed since it last was. */
        PENDING,
        /** The database holds exactly this content. */
        SYNCED,
        /**
         * The running content is a version the database has already moved past, and the running build is
         * not newer than the one that moved it: an older release is deployed. Loading it would undo the
         * newer data, so the sync refuses while any file is in this state.
         */
        ROLLED_BACK
    }
}
