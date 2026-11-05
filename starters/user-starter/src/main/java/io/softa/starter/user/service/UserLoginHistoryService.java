package io.softa.starter.user.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.user.entity.UserLoginHistory;

/**
 * UserLoginHistory Model Service Interface
 */
public interface UserLoginHistoryService extends EntityService<UserLoginHistory, Long> {

    /**
     * Insert one row as given — its tenant included, which may be empty for an event before any
     * company was chosen. In its own transaction, so a sign-in that is refused still leaves its row.
     */
    void record(UserLoginHistory row);

}