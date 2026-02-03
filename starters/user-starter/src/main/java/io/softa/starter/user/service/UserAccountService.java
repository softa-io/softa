package io.softa.starter.user.service;

import jakarta.validation.constraints.NotNull;
import java.util.Optional;

import io.softa.framework.base.context.UserInfo;
import io.softa.framework.orm.service.EntityService;
import io.softa.starter.user.dto.UserAccountDTO;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.entity.UserAccount;

/**
 * UserAccount Model Service Interface
 */
public interface UserAccountService extends EntityService<UserAccount, String> {

    /**
     * Get user by email
     *
     * @param email email
     * @return UserAccount
     */
    Optional<UserAccount> getUserByEmail(String email);

    /**
     * Get user by mobile
     *
     * @param mobile mobile
     * @return UserAccount
     */
    Optional<UserAccount> getUserByMobile(String mobile);

    /**
     * Register new user
     *
     * @param accountInfo User account information
     * @param profileInfo User profile information
     * @return UserInfo
     */
    UserInfo registerNewUser(@NotNull UserAccountDTO accountInfo, @NotNull UserProfileDTO profileInfo);

    /**
     * Register new user (legacy method for backward compatibility)
     *
     * @param email Email
     * @param mobile Mobile
     * @param password Password
     * @return UserInfo
     */
    UserInfo registerNewUser(String email, String mobile, String password);

    /**
     * Change current user's password
     *
     * @param currentPassword Current password
     * @param newPassword New password
     */
    void changeMyPassword(String currentPassword, String newPassword);

    /**
     * Force reset user password (admin operation)
     *
     * @param userId User ID
     * @param newPassword New password
     * @return Success status
     */
    boolean forceResetPassword(String userId, String newPassword);
}