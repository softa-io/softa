package io.softa.starter.user.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.entity.UserAuthFailure;
import io.softa.starter.user.service.UserAuthFailureService;

/**
 * UserAuthFailure Model Service Implementation
 */
@Service
public class UserAuthFailureServiceImpl extends EntityServiceImpl<UserAuthFailure, String> implements UserAuthFailureService {

}