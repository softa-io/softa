package io.softa.starter.user.service.impl;

import java.util.Optional;
import com.nimbusds.jwt.JWTClaimsSet;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.starter.user.config.OAuthProperties;
import io.softa.starter.user.dto.*;
import io.softa.starter.user.enums.OAuthProvider;
import io.softa.starter.user.oauth2.GoogleProvider;
import io.softa.starter.user.oauth2.LinkedInProvider;
import io.softa.starter.user.oauth2.TikTokProvider;
import io.softa.starter.user.oauth2.XProvider;
import io.softa.starter.user.service.OAuth2Service;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserAuthProviderService;
import io.softa.starter.user.service.UserProfileService;
import io.softa.starter.user.util.TokenVerifierUtil;

@Slf4j
@Service
public class OAuth2ServiceImpl implements OAuth2Service {

    @Autowired
    private OAuthProperties oAuthProperties;

    @Autowired
    private UserAccountService accountService;

    @Autowired
    private UserProfileService profileService;

    @Autowired
    private UserAuthProviderService authProviderService;

    @Autowired
    private GoogleProvider googleProvider;

    @Autowired
    private TikTokProvider tikTokProvider;

    @Autowired
    private XProvider xProvider;

    @Autowired
    private LinkedInProvider linkedInProvider;

    /**
     * Generate social account username
     *
     * @param provider provider (e.g. "google", "tiktok", "x")
     * @param providerId provider id
     * @param providerUsername provider username (optional)
     * @param email email (optional)
     * @return generated username
     */
    public String generateSocialUsername(OAuthProvider provider, String providerUsername, String email,
            String providerId) {
        // Priority: email > provider username > provider id
        String providerName = provider.getProvider().toLowerCase();
        if (StringUtils.isNotBlank(email)) {
            return providerName + "_" + email.split("@")[0];
        } else if (StringUtils.isNotBlank(providerUsername)) {
            return providerName + "_" + providerUsername;
        } else {
            return providerName + "_" + providerId;
        }
    }

    /**
     * Build profileDTO from social information
     */
    public UserProfileDTO buildProfileFromSocial(String fullName, String photoUrl) {
        UserProfileDTO dto = new UserProfileDTO();
        dto.setFullName(fullName);
        // Upload photo from URL if it is a valid URL
        if (StringUtils.isNotBlank(photoUrl)) {
            FileInfo fileInfo = profileService.fetchPhotoFromURL(photoUrl, null);
            dto.setPhotoId(fileInfo.getFileId());
        }
        return dto;
    }

    /**
     * Apple Login
     *
     * @param idToken Apple id_token
     * @return Session ID
     */
    @Override
    public UserInfo loginByApple(String idToken) {
        try {
            JWTClaimsSet claims = TokenVerifierUtil.verifyAppleIdToken(idToken,
                    oAuthProperties.getApple().getClientId());

            // Extract user info to DTO
            AppleUserInfoDTO appleUserInfo = AppleUserInfoDTO.fromClaims(claims);

            // Check if email is verified
            if (Boolean.FALSE.equals(appleUserInfo.getEmailVerified())) {
                log.warn("New Apple user is not verified: {}", appleUserInfo.getEmail());
            }

            Optional<Long> optionalUserId = authProviderService.getUserIdByAuthProvider(OAuthProvider.APPLE,
                    appleUserInfo.getId());
            if (optionalUserId.isEmpty()) {
                String username = generateSocialUsername(OAuthProvider.APPLE, null, appleUserInfo.getEmail(),
                        appleUserInfo.getId());
                UserAccountDTO accountInfo = UserAccountDTO.forSocialRegistration(username, null);
                UserProfileDTO profileInfo = this.buildProfileFromSocial(appleUserInfo.getEmail(), null);
                UserInfo userInfo = accountService.registerNewUser(accountInfo, profileInfo);
                // Add auth provider
                authProviderService.addAuthProvider(userInfo.getUserId(), OAuthProvider.APPLE, appleUserInfo.getId(),
                        appleUserInfo);
                return userInfo;
            } else {
                return profileService.getUserInfo(optionalUserId.get());
            }
        } catch (Exception e) {
            throw new BusinessException("Apple login failed: {0}", e.getMessage(), e);
        }
    }

    /**
     * Login by OAuth2 Credential
     *
     * @param oAuthCredential OAuth credential
     * @return UserInfo
     */
    @Override
    public UserInfo loginByOAuth2(OAuthCredential oAuthCredential) {
        return switch (oAuthCredential.getProvider()) {
            case GOOGLE -> {
                Assert.isTrue(oAuthProperties.getGoogle().isEnable(), "Google OAuth is not enabled");
                yield loginByGoogleOAuth2(oAuthCredential);
            }
            case TIKTOK -> {
                Assert.isTrue(oAuthProperties.getTiktok().isEnable(), "TikTok OAuth is not enabled");
                yield loginByTikTokOAuth2(oAuthCredential);
            }
            case X -> {
                Assert.isTrue(oAuthProperties.getX().isEnable(), "X OAuth is not enabled");
                yield loginByXOAuth2(oAuthCredential);
            }
            case LINKED_IN -> {
                Assert.isTrue(oAuthProperties.getLinkedin().isEnable(), "LinkedIn OAuth is not enabled");
                yield loginByLinkedInOAuth2(oAuthCredential);
            }
            default -> throw new BusinessException("Unsupported OAuth provider: " + oAuthCredential.getProvider());
        };
    }

    /**
     * Login by Google OAuth2
     *
     * @param oAuthCredential oauth credential
     * @return UserInfo
     */
    private UserInfo loginByGoogleOAuth2(OAuthCredential oAuthCredential) {
        try {
            // 1. Exchange OAuth Credential for tokens (contains access_token and id_token)
            GoogleTokenResponseDTO tokenResponse = googleProvider.exchangeCodeForTokens(oAuthCredential);

            // 2. Verify and parse ID Token to get user info
            JWTClaimsSet claims = TokenVerifierUtil.verifyGoogleIdToken(
                    tokenResponse.getIdToken(), oAuthProperties.getGoogle().getClientId());

            // Extract user info to DTO
            GoogleUserInfoDTO googleUserInfo = GoogleUserInfoDTO.fromClaims(claims);
            String googleId = googleUserInfo.getId();
            String name = googleUserInfo.getName();
            String email = googleUserInfo.getEmail();
            // Check if email is verified
            if (Boolean.FALSE.equals(googleUserInfo.getEmailVerified())) {
                log.warn("New Google user is not verified: {}", googleUserInfo.getEmail());
            }

            // 3. Check if user exists
            Optional<Long> optionalUserId = authProviderService.getUserIdByAuthProvider(OAuthProvider.GOOGLE,
                    googleId);
            if (optionalUserId.isEmpty()) {
                String username = generateSocialUsername(OAuthProvider.GOOGLE, null, email, name);
                UserAccountDTO accountInfo = UserAccountDTO.forSocialRegistration(username, name);
                UserProfileDTO profileInfo = this.buildProfileFromSocial(name, googleUserInfo.getPicture());
                UserInfo userInfo = accountService.registerNewUser(accountInfo, profileInfo);
                // Add auth provider
                authProviderService.addAuthProvider(userInfo.getUserId(), OAuthProvider.GOOGLE, googleId,
                        googleUserInfo);
                return userInfo;
            } else {
                return profileService.getUserInfo(optionalUserId.get());
            }
        } catch (Exception e) {
            throw new BusinessException("Google login failed: {0}", e.getMessage(), e);
        }
    }

    /**
     * TikTok OAuth2 Login
     *
     * @param oAuthCredential OAuth credential
     * @return UserInfo
     */
    private UserInfo loginByTikTokOAuth2(OAuthCredential oAuthCredential) {
        try {
            // 1. Exchange TikTok code for tokens
            TikTokTokenResponseDTO tokenResponse = tikTokProvider.exchangeTikTokCodeForTokens(oAuthCredential);

            // 2. Get user info using access token
            TikTokUserInfoDTO tikTokUserInfo = tikTokProvider.getTikTokUserInfo(tokenResponse.getAccessToken());

            String openId = tikTokUserInfo.getOpenId();
            String displayName = tikTokUserInfo.getDisplayName();
            String photoUrl = tikTokUserInfo.getAvatarUrl100();

            // Check if email is verified
            if (Boolean.FALSE.equals(tikTokUserInfo.getIsVerified())) {
                log.warn("New TikTok user is not verified: {}", displayName);
            }

            // 3. Check if user exists
            Optional<Long> optionalUserId = authProviderService.getUserIdByAuthProvider(OAuthProvider.TIKTOK, openId);
            if (optionalUserId.isPresent()) {
                return profileService.getUserInfo(optionalUserId.get());
            } else {
                String username = generateSocialUsername(OAuthProvider.TIKTOK, null, null, displayName);
                UserAccountDTO accountInfo = UserAccountDTO.forSocialRegistration(username, displayName);
                UserProfileDTO profileInfo = this.buildProfileFromSocial(displayName, photoUrl);
                UserInfo userInfo = accountService.registerNewUser(accountInfo, profileInfo);
                // Add auth provider
                authProviderService.addAuthProvider(userInfo.getUserId(), OAuthProvider.TIKTOK, openId, tikTokUserInfo);
                return userInfo;
            }
        } catch (Exception e) {
            throw new BusinessException("TikTok login failed: {0}", e.getMessage(), e);
        }
    }

    /**
     * X (Twitter) OAuth2 Login
     *
     * @param oAuthCredential OAuth credential
     * @return UserInfo
     */
    private UserInfo loginByXOAuth2(OAuthCredential oAuthCredential) {
        try {
            // 1. Exchange X code for tokens
            XTokenResponseDTO tokenResponse = xProvider.exchangeXCodeForTokens(oAuthCredential);

            // 2. Get user info using access token
            XUserInfoDTO xUserInfo = xProvider.getXUserInfo(tokenResponse.getAccessToken());
            String xUserId = xUserInfo.getId();
            String username = xUserInfo.getUsername();
            String name = xUserInfo.getName();
            String profileImageUrl = xUserInfo.getProfileImageUrl();

            // Check if user is verified
            if (Boolean.FALSE.equals(xUserInfo.getVerified())) {
                log.warn("New X user is not verified: {}", name);
            }

            // 3. Check if user exists
            Optional<Long> optionalUserId = authProviderService.getUserIdByAuthProvider(OAuthProvider.X, xUserId);
            if (optionalUserId.isPresent()) {
                return profileService.getUserInfo(optionalUserId.get());
            } else {
                username = generateSocialUsername(OAuthProvider.X, username, null, name);
                UserAccountDTO accountInfo = UserAccountDTO.forSocialRegistration(username, name);
                UserProfileDTO profileInfo = this.buildProfileFromSocial(name, profileImageUrl);
                UserInfo userInfo = accountService.registerNewUser(accountInfo, profileInfo);
                // Add auth provider
                authProviderService.addAuthProvider(userInfo.getUserId(), OAuthProvider.X, xUserId, xUserInfo);
                return userInfo;
            }
        } catch (Exception e) {
            throw new BusinessException("X login failed: {0}", e.getMessage(), e);
        }
    }

    /**
     * LinkedIn OAuth2 Login
     *
     * @param oAuthCredential OAuth credential
     * @return UserInfo
     */
    private UserInfo loginByLinkedInOAuth2(OAuthCredential oAuthCredential) {
        try {
            // 1. Exchange LinkedIn code for tokens
            LinkedInTokenResponseDTO tokenResponse = linkedInProvider.exchangeLinkedInCodeForTokens(oAuthCredential);

            // 2. Get user info using access token
            String accessToken = tokenResponse.getAccessToken();
            LinkedInUserInfoDTO linkedInUserInfo = linkedInProvider.getLinkedInUserInfo(accessToken);
            String linkedInId = linkedInUserInfo.getSub();
            String name = linkedInUserInfo.getName();
            String email = linkedInUserInfo.getEmail();
            String photoUrl = linkedInUserInfo.getPicture();

            // LinkedIn doesn't provide email verification status in this API
            log.info("LinkedIn user logged in: {}", name);

            // 3. Check if user exists
            Optional<Long> optionalUserId = authProviderService.getUserIdByAuthProvider(OAuthProvider.LINKED_IN,
                    linkedInId);
            if (optionalUserId.isPresent()) {
                return profileService.getUserInfo(optionalUserId.get());
            } else {
                String username = generateSocialUsername(OAuthProvider.LINKED_IN, null, email, name);
                UserAccountDTO accountInfo = UserAccountDTO.forSocialRegistration(username, name);
                UserProfileDTO profileInfo = this.buildProfileFromSocial(name, photoUrl);
                UserInfo userInfo = accountService.registerNewUser(accountInfo, profileInfo);
                // Add auth provider
                authProviderService.addAuthProvider(userInfo.getUserId(), OAuthProvider.LINKED_IN, linkedInId,
                                linkedInUserInfo);
                return userInfo;
            }
        } catch (Exception e) {
            throw new BusinessException("LinkedIn login failed: {0}", e.getMessage(), e);
        }
    }

}
