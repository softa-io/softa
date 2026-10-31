package io.softa.starter.user.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.annotation.SkipPermissionCheck;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.entity.UserLoginHistory;
import io.softa.starter.user.service.UserLoginHistoryService;

/**
 * UserLoginHistory Model Service Implementation
 */
@Service
public class UserLoginHistoryServiceImpl extends EntityServiceImpl<UserLoginHistory, Long> implements UserLoginHistoryService {

    // @CrossTenant keeps the row's own tenant instead of stamping the caller's (there is none before
    // a company is chosen); @SkipPermissionCheck because the person signing in holds no grant on it.
    @Override
    @CrossTenant
    @SkipPermissionCheck
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UserLoginHistory row) {
        this.createOne(row);
    }

}