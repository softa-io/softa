package io.softa.starter.user.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.softa.framework.orm.service.SharedModelTenantBinding;

/**
 * A person's profile, read inside a customer tenant, is one of that tenant's people: the profile an
 * account of the tenant belongs to, or belonged to before a re-hire reopened it.
 *
 * <p>{@code UserProfile} is shared — one row per person, however many tenants employ them — so tenant
 * isolation never narrowed it, and a role holding the account view, or a tenant admin, read every
 * tenant's people.
 */
@Configuration
public class UserProfileTenantBindingConfig {

    @Bean
    public SharedModelTenantBinding userProfileTenantBinding() {
        return new SharedModelTenantBinding("UserProfile", "UserAccount", List.of("profileId", "formerProfileId"));
    }
}
