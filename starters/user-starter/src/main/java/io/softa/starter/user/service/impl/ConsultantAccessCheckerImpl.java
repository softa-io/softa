package io.softa.starter.user.service.impl;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ConsultantAccessChecker;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;

/**
 * Whether a consultant's authorization still stands, answered from the membership the session holds.
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
 *
 * <h3>Why a cache, and why such a short one</h3>
 * The gate asks this on EVERY request a consultant makes, and answering it costs three reads: the
 * account, the consultant record, the grant. A minute of memory removes essentially all of them from
 * a working session while changing what the answer can be by at most that minute.
 *
 * <p>The two ways access ends are not the same kind of event, and the cache treats them differently.
 * Disable and revoke are somebody pressing something, so the platform's own writes evict the
 * affected memberships ({@code ConsultantServiceImpl.forgetEntryAnswers}) and take effect at once.
 * Expiry is the calendar, with nobody pressing anything — nothing to hook — so a grant that lapsed
 * at midnight can survive up to {@link #TTL_SECONDS} into the day. That is the whole cost, and it is
 * bounded; the thing this replaced, caching the answer alongside the permission snapshot, would have
 * carried it for an hour.
 */
@Component
public class ConsultantAccessCheckerImpl implements ConsultantAccessChecker {

    /**
     * How long one membership's answer is trusted. Short on purpose: this is the only window in
     * which a grant that expired by the calendar still admits, and it buys back the per-request
     * reads that made the gate expensive for exactly the principal that hits it most.
     */
    static final int TTL_SECONDS = 60;

    @Autowired
    private UserAccountService accountService;

    @Autowired
    private ConsultantService consultantService;

    @Autowired
    private CacheService cacheService;

    /**
     * The cache key for one membership's answer.
     *
     * <p>Package-private and static so the write paths that must invalidate it build the same key
     * rather than a lookalike — the failure mode of a second spelling is a disabled consultant who
     * keeps working, which nothing would report.
     */
    static String cacheKey(Long accountId) {
        return "consultant:entry:" + accountId;
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public boolean stillAuthorized(Long accountId) {
        if (accountId == null) {
            return true;
        }
        Boolean cached = cacheService.get(cacheKey(accountId), Boolean.class);
        if (cached != null) {
            return cached;
        }
        boolean answer = resolve(accountId);
        cacheService.save(cacheKey(accountId), answer, TTL_SECONDS);
        return answer;
    }

    private boolean resolve(Long accountId) {
        Optional<UserAccount> account = accountService.getById(accountId);
        if (account.isEmpty() || !Boolean.TRUE.equals(account.get().getConsultant())) {
            return true;
        }
        return consultantService.canEnter(account.get().getProfileId(), account.get().getTenantId());
    }
}
