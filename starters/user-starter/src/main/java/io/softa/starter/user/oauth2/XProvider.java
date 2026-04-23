package io.softa.starter.user.oauth2;

import java.util.Base64;
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
import io.softa.starter.user.dto.OAuthCredential;
import io.softa.starter.user.dto.XTokenResponseDTO;
import io.softa.starter.user.dto.XUserInfoDTO;
import io.softa.starter.user.dto.XUserInfoResponseDTO;

@Slf4j
@Component
public class XProvider {

    @Autowired
    private OAuthProperties oAuthProperties;

    @Autowired
    @Qualifier("userOAuthRestClient")
    private RestClient restClient;

    /**
     * Get X access token using OAuth Code
     *
     * @param oAuthCredential OAuth authorization code
     * @return XTokenResponseDTO
     */
    public XTokenResponseDTO exchangeXCodeForTokens(OAuthCredential oAuthCredential) {
        try {
            String tokenUrl = "https://api.x.com/2/oauth2/token";
            OAuthProperties.OAuthConfig xConfig = oAuthProperties.getX();

            Assert.notNull(oAuthCredential.getCodeVerifier(), "PKCE Code verifier is required for X OAuth2");

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("code", oAuthCredential.getCode());
            params.add("grant_type", "authorization_code");
            params.add("redirect_uri", oAuthCredential.getRedirectUri());
            params.add("code_verifier", oAuthCredential.getCodeVerifier());

            // Confidential-client authentication per X OAuth 2.0 spec: HTTP Basic with
            // base64(client_id:client_secret).
            String credentials = xConfig.getClientId() + ":" + xConfig.getClientSecret();
            String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes());

            XTokenResponseDTO tokenResponse = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("Authorization", "Basic " + base64Credentials)
                    .body(params)
                    .retrieve()
                    .body(XTokenResponseDTO.class);

            if (tokenResponse == null || StringUtils.isBlank(tokenResponse.getAccessToken())) {
                throw new BusinessException("X OAuth token fetch failed: empty response or missing Access Token");
            }

            return tokenResponse;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("X OAuth token fetch failed.", e);
        }
    }

    /**
     * Get X User Info by Access Token from X API
     *
     * @param accessToken Access Token
     * @return XUserInfoDTO
     */
    public XUserInfoDTO getXUserInfo(String accessToken) {
        try {
            String userInfoUrl = "https://api.x.com/2/users/me?user.fields=id,name,username,profile_image_url,public_metrics,description,location,verified,created_at";

            XUserInfoResponseDTO responseBody = restClient.get()
                    .uri(userInfoUrl)
                    .headers(h -> h.setBearerAuth(accessToken))
                    .retrieve()
                    .body(XUserInfoResponseDTO.class);

            if (responseBody == null || responseBody.getData() == null) {
                throw new BusinessException("X response is invalid: \n{0}", responseBody);
            }

            return responseBody.getData();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Get X user info failed.", e);
        }
    }
}
