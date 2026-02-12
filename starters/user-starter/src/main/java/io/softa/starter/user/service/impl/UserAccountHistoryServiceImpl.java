package io.softa.starter.user.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.entity.UserAccountHistory;
import io.softa.starter.user.service.UserAccountHistoryService;

/**
 * UserAccountHistory Model Service Implementation
 */
@Service
public class UserAccountHistoryServiceImpl extends EntityServiceImpl<UserAccountHistory, Long> implements UserAccountHistoryService {

}