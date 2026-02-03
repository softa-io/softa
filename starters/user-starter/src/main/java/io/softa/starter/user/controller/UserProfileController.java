package io.softa.starter.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.entity.UserProfile;
import io.softa.starter.user.service.UserProfileService;

/**
 * UserProfile Controller
 */
@Slf4j
@Tag(name = "UserProfile Controller")
@RestController
@RequestMapping("/UserProfile")
public class UserProfileController extends EntityController<UserProfileService, UserProfile, String> {

    @Operation(summary = "Get Current User Info", description = "Retrieves the user info of the logged-in user.")
    @GetMapping("/getMyUserInfo")
    public ApiResponse<UserInfo> getMyUserInfo() {
        String userId = ContextHolder.getContext().getUserId();
        UserInfo userInfo = service.getUserInfo(userId);
        return ApiResponse.success(userInfo);
    }

    @Operation(summary = "Get Current User Profile", description = "Retrieves the profile details of the logged-in user.")
    @GetMapping("/getMyProfile")
    public ApiResponse<Map<String, Object>> getMyProfile() {
        Map<String, Object> profileMap = service.getCurrentUserProfileMap();
        return ApiResponse.success(profileMap);
    }

    @Operation(summary = "Update or Create Current User Profile")
    @PostMapping("/saveMyProfile")
    public ApiResponse<Void> saveMyProfile(@RequestBody @Valid UserProfileDTO myProfileDTO) {
        UserProfile profile = service.getCurrentUserProfile();
        mapDtoToProfile(myProfileDTO, profile);
        service.updateOne(profile);
        return ApiResponse.success();
    }

    /**
     * Helper to map UserProfileDTO to UserProfile entity for saving
     */
    private void mapDtoToProfile(UserProfileDTO dto, UserProfile profile) {
        profile.setFullName(dto.getFullName());
        profile.setChineseName(dto.getChineseName());
        profile.setBirthDate(dto.getBirthDate());
        profile.setBirthTime(dto.getBirthTime());
        profile.setBirthCity(dto.getBirthCity());
        profile.setGender(dto.getGender());
        profile.setPhoto(dto.getPhoto());
        profile.setLanguage(dto.getLanguage());
        profile.setTimezone(dto.getTimezone());
    }
}