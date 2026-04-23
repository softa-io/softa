package io.softa.starter.user.oauth2;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

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
    @Qualifier("userOAuthRestClient")
    private RestClient restClient;

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

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("grant_type", "authorization_code");
            params.add("code", oAuthCredential.getCode());
            params.add("redirect_uri", oAuthCredential.getRedirectUri());
            params.add("client_id", linkedInConfig.getClientId());
            params.add("client_secret", linkedInConfig.getClientSecret());

            LinkedInTokenResponseDTO tokenResponse = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(LinkedInTokenResponseDTO.class);

            if (tokenResponse == null || StringUtils.isBlank(tokenResponse.getAccessToken())) {
                throw new BusinessException("LinkedIn OAuth token Response is invalid");
            }
            return tokenResponse;
        } catch (BusinessException e) {
            throw e;
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
            String userInfoUrl = "https://api.linkedin.com/v2/userinfo";

            LinkedInUserInfoDTO userInfo = restClient.get()
                    .uri(userInfoUrl)
                    .headers(h -> {
                        h.setBearerAuth(accessToken);
                        h.setAccept(MediaType.parseMediaTypes("application/json"));
                    })
                    .retrieve()
                    .body(LinkedInUserInfoDTO.class);

            if (userInfo == null || StringUtils.isBlank(userInfo.getSub())) {
                throw new BusinessException("LinkedIn returned invalid user info");
            }

            log.info("LinkedIn OpenID Connect login success: {}", userInfo.getName());
            return userInfo;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Get LinkedIn user info failed.", e);
        }
    }
}
