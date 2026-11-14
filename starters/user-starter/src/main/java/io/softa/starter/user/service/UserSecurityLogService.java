package io.softa.starter.user.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.enums.SecurityLogSource;

public interface UserSecurityLogService extends EntityService<UserSecurityLog, Long> {

    /**
     * A row for {@code action}, filled with the account's tenant / id / person (when given), the
     * operator from the current context and the APP device from the request. Not yet written.
     */
    UserSecurityLog entry(SecurityAction action, SecurityLogSource source, UserAccount account, Long profileId);

    /**
     * Write the row in its own transaction. Never fails the caller: the log is evidence about a
     * change, not part of it.
     */
    void record(UserSecurityLog row);
}
