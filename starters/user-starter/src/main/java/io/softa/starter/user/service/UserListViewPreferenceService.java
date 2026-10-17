package io.softa.starter.user.service;

import io.softa.starter.user.dto.UserListViewPreferenceDTO;
import io.softa.starter.user.entity.UserListViewPreference;

import io.softa.framework.orm.service.EntityService;

/**
 * The caller's own list view arrangements. Every method acts on the signed-in account and no other.
 */
public interface UserListViewPreferenceService extends EntityService<UserListViewPreference, Long> {

    /** The caller's arrangement of a list, filtered to what they may read now; null when none. */
    UserListViewPreferenceDTO getMine(String viewStateKey);

    /** Save the non-null parts of {@code preference} over the caller's arrangement of its list. */
    UserListViewPreferenceDTO saveMine(UserListViewPreferenceDTO preference);

    /**
     * Forget part of the caller's arrangement.
     *
     * @param part {@code columns}, {@code export}, {@code settings}, or null for all three
     */
    void resetMine(String viewStateKey, String part);
}
