package io.softa.starter.user.controller;

import java.util.List;
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

/**
 * Consultant Profiles — the platform's own screen (PRD §2).
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
     * Enable or disable a consultant (PRD §2.2 row action).
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
