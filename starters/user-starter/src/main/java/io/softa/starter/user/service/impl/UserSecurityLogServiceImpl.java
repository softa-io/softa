package io.softa.starter.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.enums.SecurityLogSource;
import io.softa.starter.user.service.UserSecurityLogService;
import io.softa.starter.user.util.ClientInfo;

@Slf4j
@Service
public class UserSecurityLogServiceImpl extends EntityServiceImpl<UserSecurityLog, Long>
        implements UserSecurityLogService {

    @Override
    public UserSecurityLog entry(SecurityAction action, SecurityLogSource source, UserAccount account, Long profileId) {
        UserSecurityLog row = new UserSecurityLog();
        row.setAction(action);
        row.setSource(source);
        if (account != null) {
            row.setTenantId(account.getTenantId());
            row.setAccountId(account.getId());
        }
        row.setProfileId(profileId != null ? profileId : account == null ? null : account.getProfileId());
        // The operator is whoever is signed in — HR on a roster action, the employee in the APP;
        // nobody for the system (a nightly job) or an anonymous flow (forgot password).
        if (source != SecurityLogSource.SYSTEM && ContextHolder.existContext()) {
            row.setOperatorId(ContextHolder.getContext().getUserId());
        }
        row.setDevice(ClientInfo.current().deviceLabel());
        return row;
    }

    // @CrossTenant keeps the row's own tenant (the account's, or none for a person-level change)
    // rather than stamping the operator's; @SkipPermissionCheck because the actor holds no grant on
    // the log and must not need one to have their change recorded.
    @Override
    @CrossTenant
    @SkipPermissionCheck
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UserSecurityLog row) {
        try {
            this.createOne(row);
        } catch (RuntimeException e) {
            log.warn("Security log not written for {} of profile {}: {}",
                    row.getAction(), row.getProfileId(), e.getMessage());
        }
    }
}
