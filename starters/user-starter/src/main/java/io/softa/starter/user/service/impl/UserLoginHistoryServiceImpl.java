package io.softa.starter.user.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.entity.UserLoginHistory;
import io.softa.starter.user.service.UserLoginHistoryService;

/**
 * UserLoginHistory Model Service Implementation
 */
@Service
public class UserLoginHistoryServiceImpl extends EntityServiceImpl<UserLoginHistory, Long> implements UserLoginHistoryService {

}