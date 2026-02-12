package io.softa.starter.user.service;

import java.util.Map;

import io.softa.framework.base.context.UserInfo;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.service.EntityService;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.entity.UserProfile;

/**
 * UserProfile Model Service Interface
 */
public interface UserProfileService extends EntityService<UserProfile, Long> {

    /**
     * Get Current User Profile
     */
    UserProfile getCurrentUserProfile();

    /**
     * Get Current User Profile as Map
     */
    Map<String, Object> getCurrentUserProfileMap();

    /**
     * Get UserInfo from cache or database
     *
     * @param userId User ID
     * @return UserInfo object
     */
    UserInfo getUserInfo(Long userId);

    /**
     * Register new user profile when user register
     *
     * @param userId User ID
     * @param profileDTO User profile DTO
     * @return UserInfo object
     */
    UserInfo registerUserProfile(Long userId, UserProfileDTO profileDTO);

    /**
     * Fetch user photo from remote URL and save it locally
     *
     * @param photoUrl Photo URL
     * @param profileId UserProfile ID
     * @return FileInfo of the saved photo
     */
    FileInfo fetchPhotoFromURL(String photoUrl, Long profileId);

}