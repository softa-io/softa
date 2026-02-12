package io.softa.starter.user.service;

import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.user.entity.UserAuthProvider;
import io.softa.starter.user.enums.OAuthProvider;

/**
 * UserAuthProvider Model Service Interface
 */
public interface UserAuthProviderService extends EntityService<UserAuthProvider, Long> {

    /**
     * Get user id by provider and providerId
     *
     * @param provider Provider
     * @param providerId Provider ID
     * @return UserAuthProvider
     */
    Optional<Long> getUserIdByAuthProvider(OAuthProvider provider, String providerId);

    /**
     * Add auth provider
     *
     * @param userId User ID
     * @param provider OAuth Provider
     * @param providerId Provider ID
     * @param additionalInfo Additional Info
     */
    void addAuthProvider(Long userId, OAuthProvider provider, String providerId, Object additionalInfo);
}