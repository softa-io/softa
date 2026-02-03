package io.softa.starter.user.oauth2;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.user.config.OAuthProperties;
import io.softa.starter.user.dto.LinkedInTokenResponseDTO;
import io.softa.starter.user.dto.LinkedInUserInfoDTO;
import io.softa.starter.user.dto.OAuthCredential;

@Slf4j
@Component
public class LinkedInProvider {

    @Autowired
    private OAuthProperties oAuthProperties;

    @Autowired
    private RestTemplate restTemplate;

    /**
     * Get LinkedIn Access Token using OAuth Code based on OpenID Connect standard
     *
     * @param oAuthCredential OAuth authorization code
     * @return LinkedInTokenResponseDTO
     */
    public LinkedInTokenResponseDTO exchangeLinkedInCodeForTokens(OAuthCredential oAuthCredential) {
        try {
            String tokenUrl = "https://www.linkedin.com/oauth/v2/accessToken";
            OAuthProperties.OAuthConfig linkedInConfig = oAuthProperties.getLinkedin();

            Assert.hasText(oAuthCredential.getCode(), "Authorization code is required for LinkedIn OAuth2");
            Assert.hasText(oAuthCredential.getRedirectUri(), "Redirect URI is required for LinkedIn OAuth2");

            // Build request parameters
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("grant_type", "authorization_code");
            params.add("code", oAuthCredential.getCode());
            params.add("redirect_uri", oAuthCredential.getRedirectUri());
            params.add("client_id", linkedInConfig.getClientId());
            params.add("client_secret", linkedInConfig.getClientSecret());

            // Build headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);

            // Send POST request to exchange code for tokens
            ResponseEntity<LinkedInTokenResponseDTO> response = restTemplate.exchange(
                    tokenUrl, HttpMethod.POST, entity, LinkedInTokenResponseDTO.class);

            LinkedInTokenResponseDTO tokenResponse = response.getBody();
            if (tokenResponse == null || StringUtils.isBlank(tokenResponse.getAccessToken())) {
                throw new BusinessException("LinkedIn OAuth token Response is invalid");
            }

            return tokenResponse;
        } catch (Exception e) {
            log.error("LinkedIn OAuth token fetch failed", e);
            throw new BusinessException("LinkedIn OAuth token fetch failed: " + e.getMessage());
        }
    }

    /**
     * Get LinkedIn User Info using the userinfo endpoint defined by OpenID Connect
     *
     * @param accessToken Access Token
     * @return LinkedInUserInfoDTO
     */
    public LinkedInUserInfoDTO getLinkedInUserInfo(String accessToken) {
        try {
            // The LinkedIn OpenID Connect userinfo endpoint
            String userInfoUrl = "https://api.linkedin.com/v2/userinfo";

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setAccept(MediaType.parseMediaTypes("application/json"));

            HttpEntity<String> entity = new HttpEntity<>(headers);

            // Send GET request to fetch user info
            ResponseEntity<LinkedInUserInfoDTO> response = restTemplate.exchange(
                    userInfoUrl, HttpMethod.GET, entity, LinkedInUserInfoDTO.class);

            LinkedInUserInfoDTO userInfo = response.getBody();
            if (userInfo == null || StringUtils.isBlank(userInfo.getSub())) {
                throw new BusinessException("LinkedIn returned invalid user info");
            }

            log.info("LinkedIn OpenID Connect login success: {}", userInfo.getName());
            return userInfo;

        } catch (Exception e) {
            throw new BusinessException("Get LinkedIn user info failed.", e);
        }
    }
}