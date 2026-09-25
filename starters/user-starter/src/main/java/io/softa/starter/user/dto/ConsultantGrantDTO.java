package io.softa.starter.user.dto;

import java.time.LocalDate;
import lombok.Data;

import io.softa.starter.user.enums.AccountStatus;

/**
 * One row of the Authorized Tenants table on the platform's consultant form.
 *
 * <p>The grant plus the two things an operator cannot get from it: the company's name, and the
 * state of the membership the grant minted.
 *
 * <p><b>Why the membership's status is here.</b> A grant and the account it minted are decided by
 * two different parties — the platform authorizes, the customer's own administrator may suspend —
 * and entry needs both to agree. Shown only on the grant, an operator reading "authorized until
 * December" would have no way to see that the customer froze the account in September, and would
 * answer a consultant's "I cannot get in" with "but you are authorized". The status is the half of
 * the answer that lives on the tenant's side.
 */
@Data
public class ConsultantGrantDTO {

    private Long id;

    private Long tenantId;

    /** Null when the tenant directory is absent — the id is then all there is to show. */
    private String tenantName;

    /** Empty means open-ended; see {@code ConsultantAuthorization.endDate}. */
    private LocalDate endDate;

    /**
     * The minted membership's status, or null when no membership exists for this pair.
     *
     * <p>Null is a real state, not a missing value: a grant whose account was never minted, or was
     * removed out of band, is exactly the case an operator needs to see rather than have smoothed
     * over into a plausible-looking ACTIVE.
     */
    private AccountStatus accountStatus;
}
