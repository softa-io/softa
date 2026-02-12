package io.softa.starter.user.service.impl;

import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.constant.StringConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.FileService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.orm.utils.LambdaUtils;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.entity.UserProfile;
import io.softa.starter.user.service.UserProfileService;

/**
 * UserProfile Model Service Implementation
 */
@Slf4j
@Service
public class UserProfileServiceImpl extends EntityServiceImpl<UserProfile, Long> implements UserProfileService {

    @Autowired
    private FileService fileService;

    @Autowired
    private CacheService cacheService;

    @Autowired
    @Lazy
    private UserProfileService selfProxy;

    /**
     * Get Current User Profile
     */
    @Override
    public UserProfile getCurrentUserProfile() {
        Long userId = ContextHolder.getContext().getUserId();
        Filters profileFilters = new Filters().eq(UserProfile::getUserId, userId);
        Optional<UserProfile> profileOpt = this.searchOne(profileFilters);
        return profileOpt.orElseThrow(() -> new IllegalArgumentException("Current user profile not found."));
    }

    /**
     * Get Current User Profile as Map
     */
    @Override
    public Map<String, Object> getCurrentUserProfileMap() {
        Long userId = ContextHolder.getContext().getUserId();
        Filters profileFilters = new Filters().eq(UserProfile::getUserId, userId);
        FlexQuery flexQuery = new FlexQuery(profileFilters);
        flexQuery.setConvertType(ConvertType.REFERENCE);
        Optional<Map<String, Object>> profileOpt = this.modelService.searchOne(this.modelName, flexQuery);
        return profileOpt.orElseThrow(() -> new IllegalArgumentException("Current user profile not found."));
    }

    /**
     * Get UserInfo from cache or database
     *
     * @param userId User ID
     * @return UserInfo object
     */
    @Override
    public UserInfo getUserInfo(Long userId) {
        // Check and potentially update UserInfo cache
        String userInfoKey = RedisConstant.USER_INFO + userId;
        UserInfo userInfo = cacheService.get(userInfoKey, UserInfo.class);
        if (userInfo == null) {
            Filters filters = new Filters().eq(UserProfile::getUserId, userId);
            UserProfile profile = this.searchOne(filters).orElseThrow(
                    () -> new BusinessException("User profile not found for user ID: " + userId));
            userInfo = this.buildUserInfo(profile);
            this.refreshUserInfo(userId, userInfo);
        }
        return userInfo;
    }

    /**
     * Refresh UserInfo cache
     *
     * @param userId User ID
     * @param userInfo UserInfo object
     */
    private void refreshUserInfo(Long userId, UserInfo userInfo) {
        String userInfoKey = RedisConstant.USER_INFO + userId;
        cacheService.save(userInfoKey, userInfo, RedisConstant.ONE_MONTH);
    }

    /**
    * Build UserInfo from UserProfile and save to cache
    *
    * @param profile UserProfile
    * @return UserInfo object
    */
    private UserInfo buildUserInfo(UserProfile profile) {
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(profile.getUserId());
        userInfo.setName(profile.getFullName());
        userInfo.setLanguage(profile.getLanguage());
        userInfo.setTimezone(profile.getTimezone());
        userInfo.setTenantId(profile.getTenantId());
        if (profile.getPhotoId() != null) {
            // The photo URL expires in one quarter (90 days), longer than the user info cache expiration time
            Optional<FileInfo> fileInfoOpt = fileService.getByFileId(profile.getPhotoId(), RedisConstant.ONE_QUARTER);
            fileInfoOpt.ifPresent(fileInfo -> userInfo.setPhotoUrl(fileInfo.getUrl()));
        }
        return userInfo;
    }

    /**
     * Build UserProfile from UserProfileDTO
     *
     * @param profileInfo UserProfileDTO
     * @return UserProfile object
     */
    private UserProfile buildUserProfile(UserProfileDTO profileInfo) {
        Context context = ContextHolder.getContext();
        UserProfile userProfile = new UserProfile();
        userProfile.setFullName(profileInfo.getFullName());
        userProfile.setChineseName(profileInfo.getChineseName());
        userProfile.setGender(profileInfo.getGender());
        userProfile.setBirthDate(profileInfo.getBirthDate());
        userProfile.setBirthTime(profileInfo.getBirthTime());
        userProfile.setBirthCity(profileInfo.getBirthCity());
        userProfile.setPhotoId(profileInfo.getPhotoId());
        userProfile.setLanguage(Optional.ofNullable(profileInfo.getLanguage()).orElse(context.getLanguage()));
        userProfile.setTimezone(Optional.ofNullable(profileInfo.getTimezone()).orElse(context.getTimezone()));
        userProfile.setTenantId(context.getTenantId());
        return userProfile;
    }

    /**
     * Register new user profile when user register
     *
     * @param userId User ID
     * @param profileDTO User profile DTO
     * @return UserInfo object
     */
    @Override
    public UserInfo registerUserProfile(Long userId, UserProfileDTO profileDTO) {
        // Create user profile
        UserProfile userProfile = this.buildUserProfile(profileDTO);
        userProfile.setUserId(userId);
        Long profileId = this.createOne(userProfile);

        // Build UserInfo and upload photo if photo is not empty
        UserInfo userInfo = this.buildUserInfo(userProfile);

        // Update UserInfo cache
        this.refreshUserInfo(userId, userInfo);

        return userInfo;
    }

    /**
     * Fetch user photo from remote URL and save it locally
     *
     * @param photoUrl Photo URL
     * @param profileId Profile ID
     * @return FileInfo of the saved photo
     */
    @Override
    public FileInfo fetchPhotoFromURL(String photoUrl, Long profileId) {
        if (StringUtils.isNotBlank(photoUrl) && (
                photoUrl.startsWith(StringConstant.HTTP_PREFIX)
                        || photoUrl.startsWith(StringConstant.HTTPS_PREFIX))) {
            String fieldName = LambdaUtils.getAttributeName(UserProfile::getPhotoId);
            try {
                // The photo URL expires in one quarter (90 days), longer than the user info cache expiration time
                return fileService.uploadFromUrl(this.modelName, profileId, fieldName, photoUrl, RedisConstant.ONE_QUARTER);
            } catch (Exception e) {
                log.error("Failed to upload photo from URL: {}", photoUrl, e);
            }
        }
        return null;
    }

}