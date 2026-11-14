package io.softa.framework.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.web.config.AppVersionProperties;
import io.softa.framework.web.response.ApiResponse;

/**
 * The versions of the APP the server supports, asked by the APP at launch and when it comes back to
 * the foreground. It has to be reachable by an APP too old to do anything else, so an application
 * with an APP declares {@code /app/version} anonymous (see the softa-web README).
 */
@Tag(name = "App Version")
@RestController
@RequestMapping("/app/version")
@RequiredArgsConstructor
public class AppVersionController {

    private final AppVersionProperties versions;

    /**
     * @param minVersion    below it, the APP must upgrade
     * @param latestVersion below it, the APP offers an upgrade the person may postpone
     */
    public record VersionView(String minVersion, String latestVersion, String storeUrl, String releaseNotes) {
    }

    @GetMapping
    @Operation(summary = "The minimum and latest APP versions of a platform, and where to get it")
    public ApiResponse<VersionView> version(@RequestParam(required = false) String platform) {
        // Asked for here rather than by the binding: a missing parameter there ends as a server error.
        if (StringUtils.isBlank(platform)) {
            throw new IllegalArgumentException("The platform parameter is required.");
        }
        AppVersionProperties.Release release = versions.of(platform);
        if (release == null) {
            throw new BusinessException("Unknown platform: {0}", platform);
        }
        return ApiResponse.success(new VersionView(release.getMinVersion(), release.getLatestVersion(),
                release.getStoreUrl(), release.getReleaseNotes()));
    }
}
