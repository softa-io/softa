package io.softa.starter.user.dto;

import io.softa.starter.user.enums.AccountStatus;

/**
 * The account half of the "Me" header: what the person signed in here holds, with every contact
 * masked. The employee half (name, code, photo) belongs to whichever module keeps the employee
 * record, and is added there.
 *
 * @param maskedLoginMobile the person's login mobile, masked as the join page shows it
 * @param maskedEmail       this company's enterprise email on the account, masked; null when none
 * @param tenantName        the company signed into
 * @param tenantCount       the companies the person can see in the picker: Active, Locked and Frozen
 * @param accountStatus     this company's account status as the person sees it — Locked is derived
 *                          from the person's password lock
 */
public record MyAccountSummary(String maskedLoginMobile, String maskedEmail, String tenantName,
                               int tenantCount, AccountStatus accountStatus) {
}
