package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Which of a user's delegation rules a list shows ({@code POST /flow/delegations/search}). Not stored: each box is a
 * fixed condition on the rule, and both list the newest rule first unless the request sorts otherwise.
 */
@Getter
@AllArgsConstructor
public enum FlowDelegationBox {

    /** The rules routing the user's own tasks, whatever their status — an administrator's made for them included. */
    MINE("Mine"),

    /** The rules routing others' tasks to the user, now or later: neither cancelled nor ended. */
    TO_ME("ToMe");

    @JsonValue
    private final String type;
}
