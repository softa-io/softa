package io.softa.starter.user.oauth2;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.user.config.OAuthProperties;
import io.softa.starter.user.dto.GoogleTokenResponseDTO;
import io.softa.starter.user.dto.OAuthCredential;

@Slf4j
@Component
public class GoogleProvider {

    @Autowired
    private OAuthProperties oAuthProperties;

    @Autowired
    private RestTemplate restTemplate;

    /**
     * Exchange OAuth authorization code for access token and ID token
     *
     * @param oAuthCredential OAuth authorization code
     * @return GoogleTokenResponseDTO with access token and ID token
     */
    public GoogleTokenResponseDTO exchangeCodeForTokens(OAuthCredential oAuthCredential) {
        try {
            String tokenUrl = "https://oauth2.googleapis.com/token";
            OAuthProperties.OAuthConfig googleConfig = oAuthProperties.getGoogle();

            // Build request parameters
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("client_id", googleConfig.getClientId());
            params.add("client_secret", googleConfig.getClientSecret());
            params.add("code", oAuthCredential.getCode());
            params.add("grant_type", "authorization_code");
            params.add("redirect_uri", oAuthCredential.getRedirectUri());

            // Build headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);

            // Send POST request to exchange code for tokens
            ResponseEntity<GoogleTokenResponseDTO> response = restTemplate.exchange(
                    tokenUrl, HttpMethod.POST, entity, GoogleTokenResponseDTO.class);

            GoogleTokenResponseDTO tokenResponse = response.getBody();
            if (tokenResponse == null || StringUtils.isBlank(tokenResponse.getIdToken())) {
                throw new BusinessException("Google OAuth token fetch failed: empty response or missing ID token");
            }
            return tokenResponse;
        } catch (Exception e) {
            throw new BusinessException("Google OAuth token fetch failed.", e);
        }
    }

}
