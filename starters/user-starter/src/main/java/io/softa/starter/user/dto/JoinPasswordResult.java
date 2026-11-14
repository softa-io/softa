package io.softa.starter.user.dto;

/**
 * What setting the password on the /join flow finished.
 *
 * @param completed true on an Unbind &amp; Re-invite link: the membership is already the person's again
 *                  and active, and the page shows "done" — there is no confirm-join step. False on
 *                  any other invitation: confirm joining next.
 * @param prefill   when completed, the contact the person just verified, in full, so the "done"
 *                  page can hand it to the APP's sign-in screen — as confirmJoin does; null otherwise
 */
public record JoinPasswordResult(boolean completed, AuthenticationResult.JoinPrefill prefill) {

    /** Not completed: the confirm step follows. */
    public static JoinPasswordResult notCompleted() {
        return new JoinPasswordResult(false, null);
    }
}
