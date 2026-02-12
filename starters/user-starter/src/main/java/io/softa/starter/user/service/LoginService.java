package io.softa.starter.user.service;

import io.softa.framework.base.context.UserInfo;

/**
 * UserAccount Model Service Interface
 */
public interface LoginService {

    /**
     * Send email verification code
     *
     * @param email Email address
     */
    void sendEmailCode(String email);

    /**
     * Send mobile verification code
     *
     * @param mobile Mobile number
     */
    void sendMobileCode(String mobile);

    /**
     * User login by email verification code
     *
     * @param email Email address
     * @param code  Verification code
     * @return UserInfo
     */
    UserInfo loginByEmailCode(String email, String code);

    /**
     * User login by mobile verification code
     *
     * @param mobile Mobile number
     * @param code   Verification code
     * @return UserInfo
     */
    UserInfo loginByMobileCode(String mobile, String code);

    /**
     * Generate a new session ID for a user
     *
     * @param userId User ID
     * @return Session ID
     */
    String generateSessionId(Long userId);

    /**
     * User registration by email and password
     *
     * @param email    email
     * @param password Password
     * @return UserInfo
     */
    UserInfo registerByEmailAndPassword(String email, String password);

    /**
     * User login by email and password
     *
     * @param email    Email address
     * @param password Password
     * @return UserInfo
     */
    UserInfo loginByEmailAndPassword(String email, String password);

    /**
     * Forget password, send password reset email
     *
     * @param username Username or email
     */
    void forgetPassword(String username);

    /**
     * Reset password using reset token
     *
     * @param token       Password reset token
     * @param newPassword New password
     */
    void resetPassword(String token, String newPassword);
}