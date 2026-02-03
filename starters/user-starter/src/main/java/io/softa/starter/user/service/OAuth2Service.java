package io.softa.starter.user.service;

import io.softa.framework.base.context.UserInfo;
import io.softa.starter.user.dto.OAuthCredential;

public interface OAuth2Service {

    /**
     * Login by Apple ID
     *
     * @param idToken Apple id_token
     * @return UserInfo
     */
    UserInfo loginByApple(String idToken);

    /**
     * Login by OAuth2
     *
     * @param oAuthCredential oauth credential
     * @return UserInfo
     */
    UserInfo loginByOAuth2(OAuthCredential oAuthCredential);

}
