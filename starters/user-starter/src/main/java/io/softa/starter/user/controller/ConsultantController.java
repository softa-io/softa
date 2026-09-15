package io.softa.starter.user.controller;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.ConsultantProfileDTO;
import io.softa.starter.user.entity.ConsultantAuthorization;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.dto.ConsultantRowDTO;

/**
 * Consultant Profiles — the platform's own screen.
 *
 * <p>Platform-side throughout: these endpoints create the people who work inside client companies
 * and decide which companies, for how long. Nothing here is reachable from a tenant, and the
 * memberships it mints are hidden from the tenant's roster — a tenant neither administers its
 * consultants nor is told which ones exist.
 *
 * <p>Its own path rather than the generic model surface because the unit of work is not a row: one
 * save carries the person, the consultant record and the whole authorization table, and applying
 * them separately would leave a half-authorized consultant if the second call never came.
 */
@Tag(name = "Consultant Profiles")
@RestController
@RequestMapping("/consultant")
public class ConsultantController {

    @Autowired
    private ConsultantService consultantService;

    /**
     * The Consultant Profiles list.
     *
     * <p>Assembled rows rather than the generic model surface: the login identifiers live on a
     * satellite pointing at the profile, so no cascade path reaches them, and Authorized Tenants is
     * a calendar question — only the grants covering today, which is what keeps this list agreeing
     * with the switcher each consultant actually sees.
     */
    @Operation(summary = "List consultant profiles with their live authorizations")
    @PostMapping("/list")
    public ApiResponse<List<ConsultantRowDTO>> list(
            @RequestParam(required = false) String search) {
        return ApiResponse.success(consultantService.list(search));
    }

    /**
     * Create or update a consultant, grants included.
     *
     * <p>If the email or mobile already belongs to somebody, that person becomes the consultant
     * rather than a second profile being made — login identifiers are globally unique, and the
     * person holding the address is the one being described. That is also what makes "employee of
     * one company, consultant for another" possible; the two memberships hang off one person.
     */
    @Operation(summary = "Create or update a consultant profile with its authorized tenants")
    @PostMapping("/save")
    public ApiResponse<Long> save(@RequestBody @Valid ConsultantProfileDTO form) {
        return ApiResponse.success(consultantService.save(form));
    }

    /**
     * This consultant's grants, live or lapsed.
     *
     * <p>Deliberately unfiltered, unlike the tenant switcher: the platform's own form edits dates,
     * so it has to show a grant that has expired — that is the row an operator extends.
     */
    @Operation(summary = "List a consultant's authorizations, including lapsed ones")
    @PostMapping("/authorizations")
    public ApiResponse<List<ConsultantAuthorization>> authorizations(
            @RequestParam @NotNull Long profileId) {
        return ApiResponse.success(consultantService.authorizationsOf(profileId));
    }

    /**
     * Which of these acting accounts are consultants, for an audit trail's badge.
     *
     * <p>A separate question rather than a field on the audit record: change logging is generic and
     * has no business knowing consultants exist. A tenant reading its own log calls this for the
     * actors on the page.
     *
     * <p>Answerable by a tenant even though it may not administer these memberships — attribution
     * is not administration. The flag and the consultant's login email come back — the actor column
     * reads "{name} ({email})" — and nothing else: the hiding rule protects the roster —
     * mobile, grants, other customers — not the identity of somebody who changed this tenant's data.
     */
    @Operation(summary = "Flag which acting accounts are consultants, with their login email, for audit attribution")
    @PostMapping("/actors")
    public ApiResponse<Map<Long, String>> actors(@RequestBody List<Long> accountIds) {
        return ApiResponse.success(consultantService.consultantActors(accountIds));
    }

    /**
     * Enable or disable a consultant — the list's row action.
     *
     * <p>One switch over every company at once, and it leaves the grants alone — so re-enabling
     * restores exactly the access that was there, with each grant's own dates still deciding.
     * Disabling is not revoking: the grants and the memberships both stay, which is what keeps the
     * tenant's audit log able to name what this consultant did.
     */
    @Operation(summary = "Enable or disable a consultant across every authorized tenant")
    @PostMapping("/setActive")
    public ApiResponse<Void> setActive(@RequestParam @NotNull Long profileId,
                                       @RequestParam @NotNull Boolean active) {
        consultantService.setActive(profileId, active);
        return ApiResponse.success();
    }
}
