package io.softa.starter.user.service.impl;

import java.util.Optional;
import org.springframework.stereotype.Service;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.entity.UserAuthProvider;
import io.softa.starter.user.enums.OAuthProvider;
import io.softa.starter.user.service.UserAuthProviderService;

/**
 * UserAuthProvider Model Service Implementation
 */
@Service
public class UserAuthProviderServiceImpl extends EntityServiceImpl<UserAuthProvider, Long> implements UserAuthProviderService {

    /**
     * Get user id by provider and providerId
     *
     * @param provider Provider
     * @param providerId Provider ID
     * @return UserAuthProvider
     */
    @Override
    public Optional<Long> getUserIdByAuthProvider(OAuthProvider provider, String providerId) {
        Filters filters = new Filters()
                .eq(UserAuthProvider::getProvider, provider.getProvider())
                .eq(UserAuthProvider::getProviderUserId, providerId);
        FlexQuery flexQuery = new FlexQuery(filters).select(UserAuthProvider::getUserId);
        return this.searchOne(flexQuery).map(UserAuthProvider::getUserId);
    }

    /**
     * Add auth provider
     *
     * @param userId User ID
     * @param provider OAuth Provider
     * @param providerId Provider ID
     * @param additionalInfo Additional Info
     */
    @Override
    public void addAuthProvider(Long userId, OAuthProvider provider, String providerId, Object additionalInfo) {
        UserAuthProvider userAuthProvider = new UserAuthProvider();
        userAuthProvider.setUserId(userId);
        userAuthProvider.setProvider(provider);
        userAuthProvider.setProviderUserId(providerId);
        userAuthProvider.setAdditionalInfo(JsonUtils.objectToString(additionalInfo));
        this.createOne(userAuthProvider);
    }
}