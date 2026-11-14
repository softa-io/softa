package io.softa.starter.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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

    private TransactionTemplate ownTransaction;

    /** The row is written in a transaction of its own, so the change it records commits either way. */
    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

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
    //
    // Never fails the caller, and the catch has to sit OUTSIDE the row's transaction: caught inside
    // a @Transactional(REQUIRES_NEW) method, a failed insert still marks that transaction
    // rollback-only, and its commit then throws UnexpectedRollbackException at the caller — after a
    // password reset has already ended the person's sessions.
    @Override
    @CrossTenant
    @SkipPermissionCheck
    public void record(UserSecurityLog row) {
        try {
            ownTransaction.executeWithoutResult(status -> this.createOne(row));
        } catch (RuntimeException e) {
            log.warn("Security log not written for {} of profile {}: {}",
                    row.getAction(), row.getProfileId(), e.getMessage());
        }
    }
}
