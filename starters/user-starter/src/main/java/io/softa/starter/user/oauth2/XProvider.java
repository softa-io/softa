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
    private RestTemplate restTemplate;

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

            // Build request parameters
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("code", oAuthCredential.getCode());
            params.add("grant_type", "authorization_code");
            params.add("redirect_uri", oAuthCredential.getRedirectUri());
            params.add("code_verifier", oAuthCredential.getCodeVerifier());

            // Build headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            // Use Basic Auth for client authentication according to X OAuth 2.0 spec for confidential clients
            // Format: Authorization: Basic base64(client_id:client_secret)
            String credentials = xConfig.getClientId() + ":" + xConfig.getClientSecret();
            String base64Credentials = java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
            headers.set("Authorization", "Basic " + base64Credentials);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);

            // Send POST request to exchange code for tokens
            ResponseEntity<XTokenResponseDTO> response = restTemplate.exchange(
                    tokenUrl, HttpMethod.POST, entity, XTokenResponseDTO.class);

            XTokenResponseDTO tokenResponse = response.getBody();
            if (tokenResponse == null || StringUtils.isBlank(tokenResponse.getAccessToken())) {
                throw new BusinessException("X OAuth token fetch failed: empty response or missing Access Token");
            }

            return tokenResponse;
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

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);

            HttpEntity<String> entity = new HttpEntity<>(headers);

            // Convert the response to XUserInfoResponseDTO
            ResponseEntity<XUserInfoResponseDTO> response = restTemplate.exchange(
                    userInfoUrl, HttpMethod.GET, entity, XUserInfoResponseDTO.class);

            XUserInfoResponseDTO responseBody = response.getBody();
            if (responseBody == null || responseBody.getData() == null) {
                throw new BusinessException("X response is invalid: \n{0}", response);
            }

            return responseBody.getData();
        } catch (Exception e) {
            throw new BusinessException("Get X user info failed.", e);
        }
    }
}
