package io.softa.starter.user.dto;

/**
 * What setting the password on the /join flow finished.
 *
 * @param completed true on an Unbind &amp; Re-invite link: the membership is already the person's again
 *                  and active, and the page shows "done" — there is no confirm-join step. False on
 *                  any other invitation: confirm joining next.
 */
public record JoinPasswordResult(boolean completed) {
}
