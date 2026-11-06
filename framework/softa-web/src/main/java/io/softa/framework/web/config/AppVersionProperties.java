package io.softa.framework.web.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The APP versions the server supports, per platform ({@code ios}, {@code android}), changed in the
 * configuration when an APP version ships — there is no admin page. With nothing configured no
 * request is refused.
 *
 * <pre>
 * app:
 *   versions:
 *     ios:
 *       min-version: 1.0.0
 *       latest-version: 1.2.0
 *       store-url: https://apps.apple.com/...
 *       release-notes: ...
 *   upgrade-exempt-paths:
 *     - DELETE /devices/*
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppVersionProperties {

    private Map<String, Release> versions = new HashMap<>();

    /**
     * Signed-in endpoints an APP below the minimum version may still call, in addition to signing
     * out, which is always open: what an APP stuck on the upgrade screen still has to be able to do.
     * Each entry is a path pattern ({@code /devices/*}), optionally after an HTTP method
     * ({@code DELETE /devices/*}); without a method it covers them all. Paths exclude the context path.
     */
    private List<String> upgradeExemptPaths = new ArrayList<>();

    /** One platform's versions. */
    @Data
    public static class Release {
        /** Below it the APP must upgrade before anything else works. */
        private String minVersion;
        /** Below it the APP offers an upgrade the person may postpone. */
        private String latestVersion;
        private String storeUrl;
        private String releaseNotes;
    }

    /** The configured versions of a platform, or null when it has none. */
    public Release of(String platform) {
        return platform == null ? null : versions.get(platform.trim().toLowerCase());
    }

    /**
     * Whether {@code version} is older than {@code floor}, comparing the numeric parts in order
     * ({@code 1.10.0} is newer than {@code 1.9.3}); a missing part counts as 0, and a leading
     * {@code v} or other non-digits are ignored ({@code v2.0.0} is {@code 2.0.0}). Not older when
     * either side is blank: no floor configured, or a version the APP did not send, blocks nothing.
     */
    public static boolean isOlder(String version, String floor) {
        if (version == null || floor == null || version.isBlank() || floor.isBlank()) {
            return false;
        }
        String[] mine = parts(version);
        String[] min = parts(floor);
        for (int i = 0; i < Math.max(mine.length, min.length); i++) {
            long a = part(mine, i);
            long b = part(min, i);
            if (a != b) {
                return a < b;
            }
        }
        return false;
    }

    // Leading non-digits dropped first: splitting "v2.0.0" as it stands yields an empty first part,
    // which read as 0 and made v2.0.0 older than 1.0.0.
    private static String[] parts(String version) {
        return version.trim().replaceFirst("^[^0-9]+", "").split("[^0-9]+");
    }

    private static long part(String[] parts, int index) {
        if (index >= parts.length || parts[index].isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
