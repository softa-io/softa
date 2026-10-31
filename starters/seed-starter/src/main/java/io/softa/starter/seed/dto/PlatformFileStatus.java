package io.softa.starter.seed.dto;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.starter.seed.manifest.SeedFileMode;
import io.softa.starter.seed.manifest.SeedLevel;

/**
 * A platform seed file of the running build against what the platform last loaded from it.
 */
@Schema(name = "PlatformFileStatus", description = "A platform seed file of the running build against what the "
        + "platform last loaded from it")
public record PlatformFileStatus(

        @Schema(description = "File name, as the manifest and the load records name it")
        String fileName,

        @Schema(description = "PLATFORM_GLOBAL: shared rows (data-system/); PLATFORM_TENANT: the platform tenant's own "
                + "rows (data-platform/)")
        SeedLevel level,

        @Schema(description = "The file's mode in the manifest. overwrite: loads whole from this page whenever it "
                + "changes. startup: the build owns the file — it loads at startup, before the application takes "
                + "traffic, and never from this page")
        SeedFileMode mode,

        @Schema(description = "Where the file stands; NOT_LOADED, CHANGED and FAILED load on the next platform load, "
                + "or at the next start for a file the build owns")
        FileState state,

        @Schema(description = "SHA-256 of the file in the running build, CRLF read as LF")
        String checksum,

        @Schema(description = "Checksum of the version last loaded successfully; null when never loaded")
        String loadedChecksum,

        @Schema(description = "When it was last loaded successfully")
        LocalDateTime loadedAt,

        @Schema(description = "Who last loaded it successfully")
        String loadedBy,

        @Schema(description = "Why the latest attempt failed, when it did")
        String lastError
) {
}
