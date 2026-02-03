package io.softa.starter.user.service;

import java.util.Map;

import io.softa.framework.base.context.UserInfo;
import io.softa.framework.orm.service.EntityService;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.entity.UserProfile;

/**
 * UserProfile Model Service Interface
 */
public interface UserProfileService extends EntityService<UserProfile, String> {

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
    UserInfo getUserInfo(String userId);

    /**
     * Register new user profile when user register
     *
     * @param userId User ID
     * @param profileDTO User profile DTO
     * @return UserInfo object
     */
    UserInfo registerUserProfile(String userId, UserProfileDTO profileDTO);

    /**
     * Fetch user photo from remote server, if photo is not empty.
     *
     * @param userId User ID
     * @param profileId User profile ID
     * @param photoUrl Photo URL
     */
    void asyncFetchPhoto(String userId, String profileId, String photoUrl);

}