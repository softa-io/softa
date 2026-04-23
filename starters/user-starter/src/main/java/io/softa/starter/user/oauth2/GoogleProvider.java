package io.softa.starter.user.oauth2;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

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
    @Qualifier("userOAuthRestClient")
    private RestClient restClient;

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

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("client_id", googleConfig.getClientId());
            params.add("client_secret", googleConfig.getClientSecret());
            params.add("code", oAuthCredential.getCode());
            params.add("grant_type", "authorization_code");
            params.add("redirect_uri", oAuthCredential.getRedirectUri());

            GoogleTokenResponseDTO tokenResponse = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(GoogleTokenResponseDTO.class);

            if (tokenResponse == null || StringUtils.isBlank(tokenResponse.getIdToken())) {
                throw new BusinessException("Google OAuth token fetch failed: empty response or missing ID token");
            }
            return tokenResponse;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Google OAuth token fetch failed.", e);
        }
    }

}
