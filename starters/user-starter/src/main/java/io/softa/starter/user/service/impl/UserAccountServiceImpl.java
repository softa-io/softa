package io.softa.starter.user.service.impl;

import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.security.PasswordUtils;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.dto.UserAccountDTO;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserProfileService;

/**
 * UserAccount Model Service Implementation
 */
@Slf4j
@Service
public class UserAccountServiceImpl extends EntityServiceImpl<UserAccount, String> implements UserAccountService {

    @Autowired
    private UserProfileService profileService;

    @Override
    public Optional<UserAccount> getUserByEmail(String email) {
        Filters filters = new Filters().eq(UserAccount::getEmail, email);
        return this.searchOne(filters);
    }

    @Override
    public Optional<UserAccount> getUserByMobile(String mobile) {
        Filters filters = new Filters().eq(UserAccount::getMobile, mobile);
        return this.searchOne(filters);
    }

    private UserAccount buildUserAccount(UserAccountDTO accountInfo) {
        Context context = ContextHolder.getContext();
        UserAccount userAccount = new UserAccount();
        userAccount.setEmail(accountInfo.getEmail());
        userAccount.setMobile(accountInfo.getMobile());
        userAccount.setUsername(accountInfo.getUsername());
        userAccount.setNickname(accountInfo.getNickname());
        userAccount.setStatus(AccountStatus.ACTIVE);
        userAccount.setTenantId(context.getTenantId());
        return userAccount;
    }

    /**
     * Register new user
     * Create user account and user profile, return UserInfo
     *
     * @param accountInfo User account information
     * @param profileInfo User profile information
     * @return UserInfo
     */
    @Override
    @Transactional
    public UserInfo registerNewUser(@NotNull UserAccountDTO accountInfo, @NotNull UserProfileDTO profileInfo) {
        Assert.notBlank(accountInfo.getUsername(), "Username cannot be blank");
        try {
            // Create user account
            UserAccount userAccount = this.buildUserAccount(accountInfo);
            String userId = this.createOne(userAccount);

            // Create user profile and return UserInfo
            return profileService.registerUserProfile(userId, profileInfo);
        } catch (Exception e) {
            throw new BusinessException("User registration failed: {0}", e.getMessage(), e);
        }
    }

    /**
     * Register new user (legacy method for backward compatibility)
     * Create user account and user profile, return UserInfo
     *
     * @param email Email
     * @param mobile Mobile
     * @param password Password
     * @return UserInfo
     */
    @Override
    @Transactional
    public UserInfo registerNewUser(String email, String mobile, String password) {
        // Build account info DTO and profile info DTO
        UserAccountDTO accountInfo = new UserAccountDTO();
        UserProfileDTO profileInfo = new UserProfileDTO();

        accountInfo.setEmail(email);
        accountInfo.setMobile(mobile);
        // Set username (use email if available, otherwise use mobile)
        if (StringUtils.isNotBlank(email)) {
            accountInfo.setUsername(email);
            profileInfo.setFullName(email);
        } else if (StringUtils.isNotBlank(mobile)) {
            accountInfo.setUsername(mobile);
            profileInfo.setFullName(mobile);
        }

        // Create user account
        UserAccount userAccount = this.buildUserAccount(accountInfo);
        if (StringUtils.isNotBlank(password)) {
            String salt = PasswordUtils.generateSalt();
            String hashedPassword = PasswordUtils.hashPassword(password, salt);
            userAccount.setPasswordSalt(salt);
            userAccount.setPassword(hashedPassword);
        }
        String userId = this.createOne(userAccount);

        // Create user profile and return UserInfo
        return profileService.registerUserProfile(userId, profileInfo);
    }

    @Override
    @Transactional
    public void changeMyPassword(String currentPassword, String newPassword) {
        Assert.notBlank(currentPassword, "Old password cannot be empty.");
        Assert.notBlank(newPassword, "New password cannot be empty.");
        // TODO: Add password strength validation

        String userId = ContextHolder.getContext().getUserId();
        Assert.notNull(userId, "Cannot change password without logged-in user context.");

        UserAccount user = this.getById(userId).orElseThrow(() -> new BusinessException("Current user not found."));

        // Verify old password using PasswordUtils and user's salt
        String hashedOldPassword = PasswordUtils.hashPassword(currentPassword, user.getPasswordSalt());
        if (!Objects.equals(hashedOldPassword, user.getPassword())) {
            throw new BusinessException("Incorrect old password.");
        }

        // Check if new password is the same as the old one
        // Hash the new password with the *existing* salt for comparison
        String hashedNewPassword = PasswordUtils.hashPassword(newPassword, user.getPasswordSalt());
        if (Objects.equals(hashedNewPassword, user.getPassword())) {
            throw new BusinessException("New password cannot be the same as the old password.");
        }

        // Update password using the *existing* salt
        user.setPassword(hashedNewPassword);
        this.updateOne(user);

        log.info("User ID {} changed their password successfully.", userId);
    }

    @Override
    @Transactional
    public boolean forceResetPassword(String userId, String newPassword) {
        Assert.notBlank(newPassword, "New password cannot be empty.");
        // TODO: Add password strength validation

        UserAccount user = this.getById(userId).orElseThrow(() -> new BusinessException("User not found."));

        // Generate new salt and hash the new password
        String newSalt = PasswordUtils.generateSalt();
        String hashedNewPassword = PasswordUtils.hashPassword(newPassword, newSalt);

        user.setPasswordSalt(newSalt);
        user.setPassword(hashedNewPassword);
        this.updateOne(user);

        log.info("User ID {} password was reset by admin.", userId);
        return true;
    }
}