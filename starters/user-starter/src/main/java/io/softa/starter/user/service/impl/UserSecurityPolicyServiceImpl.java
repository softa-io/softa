package io.softa.starter.user.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.entity.UserSecurityPolicy;
import io.softa.starter.user.service.UserSecurityPolicyService;

/**
 * UserSecurityPolicy Model Service Implementation
 */
@Service
public class UserSecurityPolicyServiceImpl extends EntityServiceImpl<UserSecurityPolicy, Long> implements UserSecurityPolicyService {

}