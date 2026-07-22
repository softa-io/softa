package io.softa.starter.user.service;

import java.util.List;
import java.util.Optional;
import jakarta.validation.constraints.NotNull;

import io.softa.framework.base.context.UserInfo;
import io.softa.framework.orm.service.EntityService;
import io.softa.starter.user.dto.UserAccountDTO;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.entity.UserAccount;

/**
 * UserAccount Model Service Interface
 */
public interface UserAccountService extends EntityService<UserAccount, Long> {

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
     * Register a new user
     *
     * @param accountInfo User account information
     * @param profileInfo User profile information
     * @return UserInfo
     */
    UserInfo registerNewUser(@NotNull UserAccountDTO accountInfo, @NotNull UserProfileDTO profileInfo);

    /**
     * Register a new user (legacy method for backward compatibility)
     *
     * @param email Email
     * @param mobile Mobile
     * @param password Password
     * @return UserInfo
     */
    UserInfo registerNewUser(String email, String mobile, String password);

    /**
     * Register an invited user — an {@link io.softa.starter.user.enums.AccountStatus#INVITED}
     * account with NO password. The user sets their password later via an invitation link.
     *
     * @param email    Email (used as the username when present)
     * @param mobile   Mobile (used as the username when email is absent)
     * @param fullName Display name for the account (nickname) and profile; when blank, falls
     *                 back to the login identifier (email or mobile)
     * @return UserInfo
     */
    UserInfo registerInvitedUser(String email, String mobile, String fullName);

    /**
     * Change the current user's password
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
    boolean forceResetPassword(Long userId, String newPassword);

    /**
     * Lock a user account
     */
    void lockAccount(Long userId);

    /**
     * Unlock a user account
     */
    void unlockAccount(Long userId, String reason);

    void unlockAccounts(List<Long> userIds, String reason);
}