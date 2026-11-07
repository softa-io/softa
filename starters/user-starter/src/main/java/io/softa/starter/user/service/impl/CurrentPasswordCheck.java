package io.softa.starter.user.service.impl;

import io.softa.starter.user.constant.AccountErrorCodes;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.LoginEvent;
import io.softa.starter.user.service.UserIdentityService;

/**
 * The current password asked for by a signed-in person — to change it, or to prove it is them
 * before a sensitive change — checked on the same terms as signing in.
 *
 * <p>Same lock, same counter: refused while the password is locked, a wrong one counts toward the
 * lock, a right one clears the count. A check of its own would be a second door onto the same
 * password with no limit on it — someone holding a signed-in session could guess the password
 * there without end, and the lock that stops them at the login screen would never trip.
 */
final class CurrentPasswordCheck {

    private CurrentPasswordCheck() {
    }

    /**
     * @param refusal what a wrong password is told; the attempts-left warning is appended to it
     */
    static void require(UserIdentityService identityService, LoginHistoryRecorder history,
                        UserIdentity identity, UserAccount account, String password, String refusal) {
        // Lock checked BEFORE the password, so "locked" versus "incorrect" cannot confirm a guess.
        if (identityService.isPasswordLocked(identity)) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.PASSWORD_LOCKED, LoginServiceImpl.lockedMessage());
        }
        if (!identityService.matchesPassword(identity, password)) {
            long failures = identityService.recordPasswordFailure(identity);
            if (failures >= UserIdentityService.FAILURES_BEFORE_LOCK) {
                history.record(LoginEvent.LOCKED, identity.getProfileId(), account.getId(), account.getTenantId(),
                        null, null, null);
                throw AccountErrorCodes.refusal(AccountErrorCodes.PASSWORD_LOCKED, LoginServiceImpl.lockedMessage());
            }
            throw LoginServiceImpl.wrongPassword(refusal, failures);
        }
        identityService.clearPasswordFailures(identity.getId());
    }
}
