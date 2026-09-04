package io.softa.starter.user.service.impl;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.service.ConsultantAccessChecker;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;

/**
 * CE3, answered from the membership the session holds.
 *
 * <p>The gate knows the request's {@code userId} — a membership — while the grant is a fact about the
 * person and the company. This resolves the one to the other and hands the whole question to
 * {@link ConsultantService#canEnter}, so "still authorized" cannot drift from what the login and the
 * tenant picker already decided; there is one definition of a live consultancy, asked from three
 * places.
 *
 * <p>Answers true for a membership that is not a consultant's at all. This is only ever consulted for
 * a caller the snapshot already identified as a consultant, but a checker that failed closed on an
 * ordinary account would lock out every employee in the deployment if that identification ever
 * changed shape — the wrong direction to be wrong in for a question that is not about them.
 */
@Component
public class ConsultantAccessCheckerImpl implements ConsultantAccessChecker {

    @Autowired
    private UserAccountService accountService;

    @Autowired
    private ConsultantService consultantService;

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public boolean stillAuthorized(Long accountId) {
        if (accountId == null) {
            return true;
        }
        Optional<UserAccount> account = accountService.getById(accountId);
        if (account.isEmpty() || !Boolean.TRUE.equals(account.get().getConsultant())) {
            return true;
        }
        return consultantService.canEnter(account.get().getProfileId(), account.get().getTenantId());
    }
}
