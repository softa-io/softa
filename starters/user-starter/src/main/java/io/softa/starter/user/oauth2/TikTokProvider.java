package io.softa.starter.user.oauth2;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
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
    @Qualifier("userOAuthRestClient")
    private RestClient restClient;

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

            // TikTok requires a form-urlencoded body string (not multipart); build it with
            // UriComponentsBuilder so special characters are percent-encoded.
            String requestBody = UriComponentsBuilder.newInstance()
                    .queryParams(params)
                    .build()
                    .getQuery();

            TikTokTokenResponseDTO tokenResponse = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("Cache-Control", "no-cache")
                    .body(requestBody)
                    .retrieve()
                    .body(TikTokTokenResponseDTO.class);

            if (tokenResponse == null || StringUtils.isBlank(tokenResponse.getAccessToken())) {
                throw new BusinessException("TikTok OAuth token response is invalid: \n{0}", tokenResponse);
            }
            return tokenResponse;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("TikTok OAuth token fetch failed.", e);
        }
    }

    @NotNull
    private MultiValueMap<String, String> buildRequestParams(OAuthCredential oAuthCredential) {
        OAuthProperties.OAuthConfig tikTokConfig = oAuthProperties.getTiktok();

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

            TikTokUserInfoResponseDTO responseBody = restClient.get()
                    .uri(userInfoUrl)
                    .headers(h -> h.setBearerAuth(accessToken))
                    .retrieve()
                    .body(TikTokUserInfoResponseDTO.class);

            if (responseBody == null || responseBody.getData() == null || responseBody.getData().getUser() == null) {
                throw new BusinessException("TikTok response is invalid: \n{0}", responseBody);
            }

            return responseBody.getData().getUser();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("TikTok OAuth user info fetch failed.", e);
        }
    }

}
