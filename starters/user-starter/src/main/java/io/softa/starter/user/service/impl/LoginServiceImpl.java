package io.softa.starter.user.service.impl;

import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.security.PasswordUtils;
import io.softa.framework.base.utils.UUIDUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.service.LoginService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserProfileService;

/**
 * UserAccount Model Service Implementation
 */
@Slf4j
@Service
public class LoginServiceImpl implements LoginService {

    @Autowired
    private CacheService cacheService;

    @Autowired
    private UserAccountService accountService;

    @Autowired
    private UserProfileService profileService;

    public static String buildLoginCodeKey(String identifier) {
        // Partition by login scenario
        return RedisConstant.VERIFICATION_CODE + "login:" + identifier;
    }

    private void generateNumericCode(String identifier) {
        // 1. Generate 6-digit numeric code
        String code = RandomStringUtils.insecure().nextNumeric(6);
        // 2. Generate Redis Key (can be partitioned by scenario, e.g., login/signup/reset_password)
        String redisKey = buildLoginCodeKey(identifier);
        // 3. Store in Redis with 5 minutes expiration
        cacheService.save(redisKey, code, RedisConstant.FIVE_MINUTES);

    }

    public void verifyCode(String identifier, String inputCode) {
        String redisKey = buildLoginCodeKey(identifier);
        String cachedCode = cacheService.get(redisKey);
        if (cachedCode == null) {
            throw new BusinessException("Verification code expired or not found");
        } if (!cachedCode.equals(inputCode)) {
            throw new BusinessException("Verification code is incorrect");
        }
    }

    private void clearCode(String identifier) {
        String redisKey = buildLoginCodeKey(identifier);
        cacheService.clear(redisKey);
    }

    @Override
    public void sendEmailCode(String email) {
        Filters filters = new Filters().eq(UserAccount::getEmail, email);
        this.generateNumericCode(email);
//        UserAccount userAccount = this.getUserByFilter(filters);
        // TODO: Send email with the code
        // emailService.sendEmail(email, "Verification Code", "Your verification code is: " + code);
    }

    @Override
    public void sendMobileCode(String mobile) {
        Filters filters = new Filters().eq(UserAccount::getMobile, mobile);
//        UserAccount userAccount = this.getUserByFilter(filters);
        // TODO: Send SMS with the code
    }

    @Override
    public UserInfo loginByEmailCode(String email, String code) {
        verifyCode(email, code);
        Optional<UserAccount> optionalUserAccount = accountService.getUserByEmail(email);
        UserInfo userInfo;
        if (optionalUserAccount.isEmpty()) {
            userInfo = accountService.registerNewUser(email, null, null);
        } else {
            userInfo = profileService.getUserInfo(optionalUserAccount.get().getId());
        }
        clearCode(email);
        return userInfo;
    }

    @Override
    public UserInfo loginByMobileCode(String mobile, String code) {
        verifyCode(mobile, code);
        Optional<UserAccount> optionalUserAccount = accountService.getUserByMobile(mobile);
        UserInfo userInfo;
        if (optionalUserAccount.isEmpty()) {
            userInfo = accountService.registerNewUser(null, mobile, null);
        } else {
            userInfo = profileService.getUserInfo(optionalUserAccount.get().getId());
        }
        clearCode(mobile);
        return userInfo;
    }

    /**
     * User login by email and password
     *
     * @param email    Email address
     * @param password Password
     * @return UserInfo
     */
    @Override
    public UserInfo loginByEmailAndPassword(String email, String password) {
        UserAccount userAccount = accountService.getUserByEmail(email).orElseThrow(
                () -> new BusinessException("User or password is incorrect."));
        String hashedPassword = PasswordUtils.hashPassword(password, userAccount.getPasswordSalt());
        if (!Objects.equals(hashedPassword, userAccount.getPassword())) {
            throw new BusinessException("User or password is incorrect.");
        }
        return profileService.getUserInfo(userAccount.getId());
    }

    /**
     * Generate a new session ID for a user
     *
     * @param userId User ID
     * @return Session ID
     */
    public String generateSessionId(String userId) {
        String sessionId = UUIDUtils.shortUUID22();
        // Store session ID -> user ID mapping in cache
        String sessionKey = RedisConstant.SESSION + sessionId;
        cacheService.save(sessionKey, userId, RedisConstant.ONE_MONTH);
        return sessionId;
    }

    /**
     * User registration by email and password
     * @param email    email
     * @param password Password
     * @return UserInfo
     */
    @Override
    @Transactional
    public UserInfo registerByEmailAndPassword(String email, String password) {
        // Check if username already exists
        Filters filter = new Filters().eq(UserAccount::getEmail, email);
        if (accountService.count(filter) > 0) {
            throw new BusinessException("Email already exists: " + email);
        }
        return accountService.registerNewUser(email, null, password);
    }

    /**
     * Forgot password, send reset password email
     */
    @Override
    public void forgetPassword(String username) {
        Assert.hasText(username, "Username cannot be empty.");

        Filters filter = Filters.of("username", Operator.EQUAL, username);
        UserAccount user = accountService.searchOne(filter).orElseThrow(
                () -> new BusinessException("User not found with username: " + username)
        );

        // --- Password Reset Token Generation Needed ---
        // TODO: Implement a secure mechanism to generate a time-limited password reset
        // token.
        // This could involve:
        // 1. Adding `resetToken` and `resetTokenExpiry` fields to UserAccount entity.
        // 2. Using a separate Cache (e.g., Redis) to store `token -> userId` mapping
        // with TTL.
        // 3. Potentially leveraging PasswordUtils if it offers token features (unlikely
        // based on current view).
        String token = "GENERATED_RESET_TOKEN"; // Placeholder
        log.info("TODO: Generate and store password reset token for user {}. Token: {}", username, token);

        // TODO: Send email to the user's registered email address (user.getEmail())
        // The email should contain a link like: [baseURL]/reset-password?token={token}
        // Requires an EmailService implementation.
        log.info("Password reset token generated for user {}. Email sending required.", username);
        throw new UnsupportedOperationException("Password reset token generation and Email sending not implemented yet.");
    }

    /**
     * Reset password using reset token
     */
    @Override
    @Transactional
    public void resetPassword(String token, String newPassword) {
        Assert.hasText(token, "Reset token cannot be empty.");
        Assert.hasText(newPassword, "New password cannot be empty.");
        // TODO: Add password strength validation

        // --- Password Reset Token Validation Needed ---
        // TODO: Implement validation for the provided reset token.
        // This requires retrieving the user associated with the token and checking its
        // validity/expiry based on the chosen generation mechanism (DB field, Cache,
        // etc.).
        // Example logic (depends heavily on implementation):
        // UserAccount user = findUserByResetToken(token); // Implement this lookup
        // if (user == null || isTokenExpired(user or tokenData)) {
        // throw new BusinessException("Invalid or expired password reset token.");
        // }
        log.warn("Password reset called, BUT TOKEN VALIDATION LOGIC IS MISSING.");
        UserAccount user = null; // Placeholder - MUST be replaced by user found via token

        if (user == null) { // Temporary check until validation is implemented
            throw new UnsupportedOperationException("Password reset token validation and user retrieval not implemented.");
        }

        // --- IMPORTANT: Generate a NEW salt when resetting password ---
        String newSalt = PasswordUtils.generateSalt();
        user.setPasswordSalt(newSalt);
        user.setPassword(PasswordUtils.hashPassword(newPassword, newSalt));

        // TODO: Invalidate the reset token after successful use (e.g., clear DB fields,
        // remove from cache).

        accountService.updateOne(user); // Update the user record
        log.info("Password reset successfully for user retrieved via token (User ID: {}).", user.getId()); // Assuming
        // getId() exists
    }

}