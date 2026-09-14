package io.softa.starter.user.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.user.entity.ConsultantAuthorization;
import io.softa.starter.user.entity.ConsultantProfile;

/**
 * Consultants — platform staff who work inside client companies for a bounded period.
 *
 * <p>A consultant is not a role a tenant grants; it is a fact the platform records about a person
 * ({@link ConsultantProfile}) plus a list of companies and dates ({@link ConsultantAuthorization}).
 * Saving a grant mints the matching {@code UserAccount}; the grant, not the account's status, is
 * what decides whether it may be entered.
 *
 * <p>Everything here is platform-side. A tenant can neither see nor change these records — its
 * account list hides consultant memberships entirely.
 */
public interface ConsultantService extends EntityService<ConsultantProfile, Long> {

    /**
     * Whether this person may enter this company as a consultant <b>right now</b>.
     *
     * <p>The single place the question is answered, because it has three independent ways to be no —
     * not a consultant, consultant disabled, grant not covering today — and any caller that
     * re-derived it would eventually check two of the three. Login, the tenant switcher and the
     * per-request gate all come here.
     *
     * <p>Evaluated against the calendar rather than stored state: a grant that ended yesterday stops
     * admitting today with nothing having run overnight, and extending it takes effect the moment the
     * date is saved.
     */
    boolean canEnter(Long profileId, Long tenantId);

    /**
     * Whether the GRANT itself still stands — enabled consultant, authorization covering today —
     * saying nothing about the company's own state.
     *
     * <p>Split from {@link #canEnter} because the tenant picker treats the two causes differently,
     * and the two rules are about different things rather than in conflict. A grant that has
     * lapsed, or a consultant who has been disabled, is a relationship that no longer exists: the row
     * goes, because listing it invites the person to ask a company that never authorized them. A live
     * grant into a company the platform has frozen is a relationship that DOES exist and cannot be
     * used right now — that row stays, greyed, carrying the reason.
     */
    boolean grantStands(Long profileId, Long tenantId);

    /**
     * The companies this consultant may enter today.
     *
     * <p>Only live grants — an expired or not-yet-started one is absent, not listed-and-greyed. That
     * is the opposite of how a frozen EMPLOYMENT is shown, and deliberately so: a frozen employment
     * is a standing relationship the person should see and can ask about, while a lapsed consultancy
     * grant is simply not access they have. Empty when the consultant is disabled.
     */
    Set<Long> enterableTenantIds(Long profileId);

    /** Whether this person is a consultant at all — enabled or not. */
    boolean isConsultant(Long profileId);

    /**
     * Create or update a consultant from the platform's form, grants included.
     *
     * <p>Handles the case the form does not draw but the tenant picker requires: the email may already
     * belong to somebody. Login identifiers are globally unique, so that person cannot be given a
     * second profile — they ARE the consultant, and the grant attaches to the person they already
     * are. That is what makes "employee of company A, consultant for company B" expressible at all;
     * two profiles sharing an address could never be shown as one picker.
     *
     * @return the consultant's profileId
     */
    Long save(io.softa.starter.user.dto.ConsultantProfileDTO form);

    /**
     * Enable or disable a consultant across every authorized tenant at once.
     *
     * <p>Distinct from revoking: the grants stay, so re-enabling restores exactly the access that
     * was there with each grant's own dates still deciding. The memberships stay too — the tenant's
     * audit log names them as the actor of what was done while the access lasted.
     */
    void setActive(Long profileId, boolean active);

    /**
     * The Consultant Profiles list, assembled.
     *
     * <p>Rows carry the person's name and login identifiers plus the companies whose grant covers
     * today — none of which a generic model read can produce: the identifiers hang off a satellite
     * pointing at the profile, and the tenant badges are a calendar question.
     *
     * @param search matched against name and email, case-insensitively; blank returns everyone
     */
    List<io.softa.starter.user.dto.ConsultantRowDTO> list(String search);

    /**
     * Which of these acting accounts belong to consultants — for labelling an audit trail.
     *
     * <p>Asked as its own question rather than carried on the audit record. Change logging is
     * generic: it captures whoever the actor was for every model, and it has no business knowing
     * that consultants exist. Answering here keeps that boundary and costs one lookup per page of
     * a log that is already being read.
     *
     * <p>Not subject to the roster's consultant hiding, deliberately. A tenant may not administer a
     * consultant's membership, but it must be able to see WHO changed its data — hiding the actor
     * would turn "a consultant edited this" into an unattributed change.
     *
     * <p>Answers with the login email as well as the flag (the actor column reads
     * "{name} ({email})" plus the Consultant tag). The email is the consultant's platform login
     * identifier, not a tenant contact — the minted membership carries none — so it is read from
     * the person's credential, not from the account row.
     *
     * @param accountIds the actors on the page being rendered
     * @return the subset that are consultant memberships, each mapped to the consultant's login
     *         email (null when the person has no credential row)
     */
    Map<Long, String> consultantActors(Collection<Long> accountIds);

    /**
     * Replace a consultant's grants with exactly this set, minting an account for each company that
     * does not have one yet.
     *
     * <p>Whole-set rather than add/remove calls: the form saves a table, and applying it as a
     * difference here keeps "what the screen showed" and "what was stored" from drifting apart.
     * Removed grants are deleted; the accounts they minted stay, because the tenant's audit log
     * points at them (a deleted account would blank the record of what the consultant did).
     *
     * @throws io.softa.framework.base.exception.BusinessException if a company already holds a
     *         non-consultant membership for this person — one person is not both staff and
     *         consultant in the same company, and the requirement blocks it rather than defining it
     */
    void replaceAuthorizations(Long profileId, List<ConsultantAuthorization> authorizations);

    /** This consultant's grants, live or not, for the platform's own screens. */
    List<ConsultantAuthorization> authorizationsOf(Long profileId);

    /**
     * Today, as the grants are read against. One method so a test can pin it and so every caller
     * agrees on the boundary — the platform's day, not the tenant's.
     */
    LocalDate today();
}
