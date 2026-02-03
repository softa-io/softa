package io.softa.starter.user.oauth2;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.user.config.OAuthProperties;
import io.softa.starter.user.dto.OAuthCredential;
import io.softa.starter.user.dto.TikTokTokenResponseDTO;
import io.softa.starter.user.dto.TikTokUserInfoDTO;
import io.softa.starter.user.dto.TikTokUserInfoResponseDTO;

/**
 * TikTok OAuth Login
 */
@Slf4j
@Component
public class TikTokProvider {

    @Autowired
    private OAuthProperties oAuthProperties;

    @Autowired
    private RestTemplate restTemplate;

    /**
     * Get TikTok access token using OAuth Code
     *
     * @param oAuthCredential OAuth authorization code
     * @return TikTokTokenResponseDTO
     */
    public TikTokTokenResponseDTO exchangeTikTokCodeForTokens(OAuthCredential oAuthCredential) {
        try {
            String tokenUrl = "https://open.tiktokapis.com/v2/oauth/token/";
            MultiValueMap<String, String> params = buildRequestParams(oAuthCredential);

            // Build form-urlencoded request body using UriComponentsBuilder
            String requestBody = UriComponentsBuilder.newInstance()
                    .queryParams(params)
                    .build()
                    .getQuery();

            // Build headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Cache-Control", "no-cache");

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            // Send POST request to exchange code for tokens
            ResponseEntity<TikTokTokenResponseDTO> response = restTemplate.exchange(
                    tokenUrl, HttpMethod.POST, entity, TikTokTokenResponseDTO.class);

            TikTokTokenResponseDTO tokenResponse = response.getBody();
            if (tokenResponse == null || StringUtils.isBlank(tokenResponse.getAccessToken())) {
                throw new BusinessException("TikTok OAuth token response is invalid: \n{0}", response);
            }
            return tokenResponse;
        } catch (Exception e) {
            throw new BusinessException("TikTok OAuth token fetch failed.", e);
        }
    }

    @NotNull
    private MultiValueMap<String, String> buildRequestParams(OAuthCredential oAuthCredential) {
        OAuthProperties.OAuthConfig tikTokConfig = oAuthProperties.getTiktok();

        // Build request parameters
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("client_key", tikTokConfig.getClientId());
        params.add("client_secret", tikTokConfig.getClientSecret());
        params.add("code", oAuthCredential.getCode());
        params.add("grant_type", "authorization_code");
        params.add("redirect_uri", oAuthCredential.getRedirectUri());
        return params;
    }



    /**
     * Get TikTok User Info
     *
     * @param accessToken Access Token
     * @return TikTokUserInfoDTO
     */
    public TikTokUserInfoDTO getTikTokUserInfo(String accessToken) {
        try {
            String userInfoUrl = "https://open.tiktokapis.com/v2/user/info/?fields=open_id,union_id,avatar_url,display_name";

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);

            HttpEntity<String> entity = new HttpEntity<>(headers);

            // Convert the response to TikTokUserInfoResponseDTO
            ResponseEntity<TikTokUserInfoResponseDTO> response = restTemplate.exchange(
                    userInfoUrl, HttpMethod.GET, entity, TikTokUserInfoResponseDTO.class);

            TikTokUserInfoResponseDTO responseBody = response.getBody();
            if (responseBody == null || responseBody.getData() == null || responseBody.getData().getUser() == null) {
                throw new BusinessException("TikTok response is invalid: \n{0}", response);
            }

            return responseBody.getData().getUser();
        } catch (Exception e) {
            throw new BusinessException("TikTok OAuth user info fetch failed.", e);
        }
    }

}
