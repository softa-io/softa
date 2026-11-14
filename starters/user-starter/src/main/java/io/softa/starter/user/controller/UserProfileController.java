package io.softa.starter.user.controller;

import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.context.UserInfo;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.service.UserProfileService;

/**
 * The three self-service endpoints of {@code UserProfile} — the ones that act on the caller's own
 * row and therefore cannot be expressed as generic CRUD.
 *
 * <p>Everything else about this model is served by {@code ModelController}'s generic surface
 * ({@code /UserProfile/searchPage}, {@code updateOne}, …). Those paths used to be reclaimed here and
 * answered with 404, from when the credentials still lived on this model and a list read would have
 * returned every person's password hash. The credentials moved to {@code UserIdentity}, so what the
 * generic surface exposes now is personal information, and it is gated the way every other model is:
 * role grants plus the endpoint registry.
 *
 * <p>Note the model is global, not tenant-scoped: a granted caller reads across tenants, since there
 * is no tenant column to narrow by.
 *
 * <p>The three endpoints below keep their historical URLs so the frontend session bootstrap and the
 * personal-settings page need no change.
 */
@Slf4j
@Tag(name = "UserProfile Controller")
@RestController
@RequestMapping("/UserProfile")
public class UserProfileController {

    @Autowired
    private UserProfileService service;

    @Operation(summary = "Get Current User Info", description = "Retrieves the user info of the logged-in user.")
    @GetMapping("/getMyUserInfo")
    public ApiResponse<UserInfo> getMyUserInfo() {
        // getMyUserInfo(), not getUserInfo(contextUserId): the self-service overload is the one that
        // waives row scope, and reading it off the context here would leave that waiver unreachable.
        return ApiResponse.success(service.withPhotoUrl(service.getMyUserInfo()));
    }

    @Operation(summary = "Get Current User Profile", description = "Retrieves the profile details of the logged-in user.")
    @GetMapping("/getMyProfile")
    public ApiResponse<Map<String, Object>> getMyProfile() {
        Map<String, Object> profileMap = service.getCurrentUserProfileMap();
        return ApiResponse.success(profileMap);
    }

    // The summary used to say "Update or Create"; the service fetches the caller's existing profile
    // and throws when there is none, so this has always been update-only.
    @Operation(summary = "Update Current User Profile")
    @PostMapping("/saveMyProfile")
    public ApiResponse<Void> saveMyProfile(@RequestBody @Valid UserProfileDTO myProfileDTO) {
        // One service call, not fetch-then-update: the row-scope waiver lives on the service method,
        // and assembling the write here would leave updateOne outside the waived span — the dialog
        // would open and the save would bounce, which is exactly the bug this replaced.
        service.saveMyProfile(myProfileDTO);
        return ApiResponse.success();
    }
}
