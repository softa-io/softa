package io.softa.starter.user.service.impl;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.entity.ConsultantAuthorization;
import io.softa.starter.user.entity.ConsultantProfile;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;

import static io.softa.framework.base.context.ContextUtils.inTenantContext;

/**
 * Consultants — see {@link ConsultantService} for what they are.
 *
 * <p>{@code @CrossTenant} throughout: every question here spans tenants by nature. Who may enter
 * company B is asked while the caller sits in company A, or in none at all — a tenant-filtered read
 * would answer "no" for access that exists, which is the failure mode that looks like a permission
 * bug and is really a scoping one.
 *
 * <p>{@code @SkipPermissionCheck} for the same reason it sits on the provisioning paths: these rows
 * are the platform's, and the tenant-side row scope has no anchor for them — left to it, a
 * consultant's own grants would fail closed and nobody could enter anywhere.
 */
@Slf4j
@Service
public class ConsultantServiceImpl extends EntityServiceImpl<ConsultantProfile, Long>
        implements ConsultantService {

    @Autowired
    private UserAccountService accountService;

    /** Grants are their own model; this service owns both sides of the pair. */
    @Autowired
    private ConsultantAuthorizationService authorizationService;

    @Autowired
    private io.softa.starter.user.service.UserIdentityService identityService;

    @Autowired
    private io.softa.starter.user.service.UserProfileService profileService;

    /** Optional: the list shows company names; absent tenant-starter → the id alone. */
    @Autowired(required = false)
    private io.softa.framework.orm.service.TenantInfoService tenantInfoService;

    /** Upper bound on one {@code consultantActors} lookup — the actors on one audit page. */
    private static final int MAX_ACTOR_LOOKUP = 500;

    @Override
    public LocalDate today() {
        return LocalDate.now();
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public boolean isConsultant(Long profileId) {
        return profileId != null && findProfile(profileId).isPresent();
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public boolean canEnter(Long profileId, Long tenantId) {
        if (profileId == null || tenantId == null) {
            return false;
        }
        // The company's own state outranks the grant (PRD CE5). A tenant the platform has frozen or
        // closed is not open to anyone, and a consultant is the one principal who would otherwise
        // walk straight in: their data access is unrestricted and their menus come from the plan, so
        // nothing further down would stop them. Absent tenant-starter there is no such state to
        // consult, and the grant alone decides.
        //
        // Asked first because it is the cheaper question (cached by the tenant directory) and rules
        // the rest out. The enabled check is grantStands' own — this used to ask it here as well, so
        // CE3, which runs this on EVERY request a consultant makes, read the consultant row twice.
        if (tenantInfoService != null && !tenantInfoService.isTenantActive(tenantId)) {
            return false;
        }
        return grantStands(profileId, tenantId);
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public boolean grantStands(Long profileId, Long tenantId) {
        if (profileId == null || tenantId == null || !isEnabled(profileId)) {
            return false;
        }
        return authorizationService.searchOne(new Filters()
                        .eq(ConsultantAuthorization::getProfileId, profileId)
                        .eq(ConsultantAuthorization::getTenantId, tenantId))
                .map(this::coversToday)
                .orElse(false);
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public Set<Long> enterableTenantIds(Long profileId) {
        if (profileId == null || !isEnabled(profileId)) {
            return Set.of();
        }
        return authorizationsOf(profileId).stream()
                .filter(this::coversToday)
                .map(ConsultantAuthorization::getTenantId)
                .collect(Collectors.toSet());
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public List<ConsultantAuthorization> authorizationsOf(Long profileId) {
        if (profileId == null) {
            return List.of();
        }
        return authorizationService.searchList(
                new Filters().eq(ConsultantAuthorization::getProfileId, profileId));
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    @Transactional
    public Long save(io.softa.starter.user.dto.ConsultantProfileDTO form) {
        Assert.notNull(form, "A consultant profile is required");
        String email = form.getEmail() == null ? null : form.getEmail().trim();
        String mobile = form.getMobile() == null ? null : form.getMobile().trim();

        Long profileId;
        if (form.getProfileId() != null) {
            // A supplied id names a person who must exist. Without this a mistyped id made a
            // consultant record pointing at nobody — saveable, listable with blank columns, and
            // impossible to fix from the form, whose person lookups all come back empty. Only the
            // client-supplied id is checked: resolveOrCreatePerson answers with a person it just
            // found or made.
            Assert.isTrue(profileService.getById(form.getProfileId()).isPresent(),
                    "No person exists with id {0}.", form.getProfileId());
            profileId = form.getProfileId();
        } else {
            profileId = resolveOrCreatePerson(email, mobile);
        }
        applyBasicInformation(profileId, form.getUsername(), email, mobile);

        // The consultant record itself: created on first save, and its Enabled/Disabled switch is
        // whatever the form says. Defaulting to enabled on create — a consultant is made in order
        // to be used, and an operator who wanted otherwise would have said so.
        ConsultantProfile profile = findProfile(profileId).orElseGet(() -> {
            ConsultantProfile fresh = new ConsultantProfile();
            fresh.setProfileId(profileId);
            return fresh;
        });
        profile.setActive(form.getActive() == null ? Boolean.TRUE : form.getActive());
        if (profile.getId() == null) {
            this.createOne(profile);
        } else {
            this.updateOne(profile);
        }

        List<ConsultantAuthorization> grants = (form.getAuthorizations() == null ? List.<io.softa.starter.user.dto.ConsultantProfileDTO.AuthorizationRow>of()
                : form.getAuthorizations()).stream().map(row -> {
                    ConsultantAuthorization grant = new ConsultantAuthorization();
                    grant.setTenantId(row.getTenantId());
                    grant.setStartDate(row.getStartDate());
                    grant.setEndDate(row.getEndDate());
                    return grant;
                }).toList();
        replaceAuthorizations(profileId, grants);
        return profileId;
    }

    /**
     * The person behind this email / mobile — the one who already exists, or a new one.
     *
     * <p>Reusing an existing person is not a convenience, it is the only correct answer: login
     * identifiers are globally unique, so a second profile carrying this address cannot be created,
     * and the person who holds it IS the consultant being described. It is also what lets someone be
     * an employee at one company and a consultant for another — one person, two kinds of membership,
     * one picker. Matching on either channel, because the operator may type whichever they know.
     */

    /**
     * Write back what the form says about the PERSON — the half of this screen that is not about the
     * consultancy at all.
     *
     * <p>It was missing entirely: {@code save} read the email and mobile into locals, used them only
     * to find or create the person, and never wrote them anywhere; the username it did not read at
     * all. So every edit to the Basic information card was accepted and silently discarded, and even
     * on create the username was dropped — which is why a consultant's name comes back as their email
     * address, the identifier {@code createPersonForJoin} seeds the person with.
     *
     * <p>A blank field means "not supplied", never "clear it". These are login identifiers: one
     * cleared by accident locks the person out of the product, and there is no screen here that would
     * explain why. The form requires all three anyway.
     *
     * <p>Changing an identifier is checked against every other person, not just consultants. They are
     * globally unique by index, so taking one that belongs to somebody else would fail at the database
     * with a constraint name instead of a sentence — and the operator's actual mistake (typing a real
     * person's address) deserves to be said out loud.
     */
    private void applyBasicInformation(Long profileId, String username, String email, String mobile) {
        String name = username == null ? null : username.trim();
        if (name != null && !name.isEmpty()) {
            profileService.getById(profileId).ifPresent(person -> {
                if (!name.equals(person.getFullName())) {
                    person.setFullName(name);
                    profileService.updateOne(person);
                    // The cached UserInfo carries the name, and nothing evicts it on a bare update —
                    // saveMyProfile does this by hand for the same reason. Keyed per MEMBERSHIP, so
                    // every one of them has to go: miss one and that tenant serves the old name until
                    // the entry expires a month later, to somebody just told the change was saved.
                    accountService.listMembershipsOf(profileId)
                            .forEach(account -> profileService.evictUserInfo(account.getId()));
                }
            });
        }

        identityService.findByProfile(profileId).ifPresent(identity -> {
            boolean changed = false;
            if (email != null && !email.isEmpty() && !email.equalsIgnoreCase(identity.getLoginEmail())) {
                requireClaimable(email, profileId);
                identity.setLoginEmail(email);
                changed = true;
            }
            if (mobile != null && !mobile.isEmpty() && !mobile.equals(identity.getLoginMobile())) {
                requireClaimable(mobile, profileId);
                identity.setLoginMobile(mobile);
                changed = true;
            }
            if (changed) {
                identityService.updateOne(identity);
            }
        });
    }

    private void requireClaimable(String identifier, Long profileId) {
        if (!identityService.isIdentifierClaimable(identifier, profileId)) {
            throw new BusinessException(
                    "\"" + identifier + "\" already belongs to someone else. Login identifiers are "
                            + "unique across the platform.");
        }
    }

    private Long resolveOrCreatePerson(String email, String mobile) {
        Optional<Long> byEmail = identityService.findByLoginIdentifier(email)
                .map(io.softa.starter.user.entity.UserIdentity::getProfileId);
        if (byEmail.isPresent()) {
            return byEmail.get();
        }
        Optional<Long> byMobile = identityService.findByLoginIdentifier(mobile)
                .map(io.softa.starter.user.entity.UserIdentity::getProfileId);
        return byMobile.orElseGet(() -> profileService.createPersonForJoin(
                email != null && !email.isBlank() ? email : mobile));
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public List<io.softa.starter.user.dto.ConsultantRowDTO> list(String search) {
        String needle = search == null ? "" : search.trim().toLowerCase();
        return this.searchList(new Filters()).stream()
                .map(this::toRow)
                .filter(row -> needle.isEmpty()
                        || (row.getUsername() != null && row.getUsername().toLowerCase().contains(needle))
                        || (row.getEmail() != null && row.getEmail().toLowerCase().contains(needle)))
                .toList();
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    public Map<Long, String> consultantActors(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        // The audit panel asks for the actors on one page. A list longer than any page could hold is
        // not a page, and an unbounded IN is how a lookup becomes a way to walk the account table.
        Assert.isTrue(accountIds.size() <= MAX_ACTOR_LOOKUP,
                "At most {0} actors can be looked up at once.", MAX_ACTOR_LOOKUP);
        // Read straight from the accounts, bypassing the roster scope that hides consultants: the
        // tenant may not administer these memberships, but it must be able to attribute changes made
        // to its own data. The flag and the login email are exposed — the PRD's actor column names
        // the consultant by email so the tenant can tell WHICH consultant, and can quote it back to
        // the platform. Nothing else: no mobile, no grant dates, nothing about other customers.
        List<UserAccount> consultants = accountService.searchList(new Filters()
                .in(UserAccount::getId, accountIds)
                .eq(UserAccount::getConsultant, true));
        if (consultants.isEmpty()) {
            return Map.of();
        }
        // One read for the page's consultants, not one per row.
        List<Long> profileIds = consultants.stream().map(UserAccount::getProfileId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> emailByProfile = profileIds.isEmpty() ? Map.of()
                : identityService.searchList(new Filters()
                                .in(io.softa.starter.user.entity.UserIdentity::getProfileId, profileIds)).stream()
                        .filter(identity -> identity.getProfileId() != null)
                        .collect(Collectors.toMap(
                                io.softa.starter.user.entity.UserIdentity::getProfileId,
                                identity -> identity.getLoginEmail() == null ? "" : identity.getLoginEmail(),
                                (a, b) -> a));
        Map<Long, String> out = new java.util.HashMap<>();
        for (UserAccount account : consultants) {
            String email = emailByProfile.get(account.getProfileId());
            out.put(account.getId(), email == null || email.isEmpty() ? null : email);
        }
        return out;
    }

    private io.softa.starter.user.dto.ConsultantRowDTO toRow(ConsultantProfile profile) {
        io.softa.starter.user.dto.ConsultantRowDTO row = new io.softa.starter.user.dto.ConsultantRowDTO();
        Long profileId = profile.getProfileId();
        row.setProfileId(profileId);
        row.setActive(profile.getActive());
        profileService.getById(profileId).ifPresent(p -> row.setUsername(p.getFullName()));
        identityService.findByProfile(profileId).ifPresent(identity -> {
            row.setEmail(identity.getLoginEmail());
            row.setMobile(identity.getLoginMobile());
        });
        // Live grants only — the badges must agree with the switcher the consultant will see. The
        // enabled check is answered from the row already in hand rather than by re-reading it.
        Set<Long> live = Boolean.TRUE.equals(profile.getActive())
                ? authorizationsOf(profileId).stream().filter(this::coversToday)
                        .map(ConsultantAuthorization::getTenantId).collect(Collectors.toSet())
                : Set.of();
        row.setAuthorizedTenants(live.stream().map(tenantId -> {
            io.softa.starter.user.dto.ConsultantRowDTO.Tenant badge =
                    new io.softa.starter.user.dto.ConsultantRowDTO.Tenant();
            badge.setTenantId(tenantId);
            badge.setTenantName(tenantInfoService == null ? null
                    : tenantInfoService.getTenantName(tenantId));
            return badge;
        }).toList());
        return row;
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    @Transactional
    public void setActive(Long profileId, boolean active) {
        ConsultantProfile profile = findProfile(profileId)
                .orElseThrow(() -> new BusinessException("That person is not a consultant."));
        profile.setActive(active);
        this.updateOne(profile);
        log.info("Consultant {} {}.", profileId, active ? "enabled" : "disabled");
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    @Transactional
    public void replaceAuthorizations(Long profileId, List<ConsultantAuthorization> wanted) {
        Assert.notNull(profileId, "profileId is required");
        List<ConsultantAuthorization> desired = wanted == null ? List.of() : wanted;
        desired.forEach(this::validate);

        // One row per company: two grants for the same pair would make "is this live today?"
        // answerable two ways. Caught here as a message rather than at the unique index, which
        // would surface as a constraint violation naming a column.
        Set<Long> seen = new HashSet<>();
        desired.forEach(a -> {
            if (!seen.add(a.getTenantId())) {
                throw new BusinessException("That company is authorized twice — one grant per company.");
            }
        });

        Map<Long, ConsultantAuthorization> existing = authorizationsOf(profileId).stream()
                .collect(Collectors.toMap(ConsultantAuthorization::getTenantId, Function.identity()));

        for (ConsultantAuthorization want : desired) {
            ConsultantAuthorization have = existing.remove(want.getTenantId());
            if (have == null) {
                want.setProfileId(profileId);
                authorizationService.createOne(want);
                mintMembership(profileId, want.getTenantId());
            } else if (!have.getStartDate().equals(want.getStartDate())
                    || !have.getEndDate().equals(want.getEndDate())) {
                have.setStartDate(want.getStartDate());
                have.setEndDate(want.getEndDate());
                authorizationService.updateOne(have);
            }
        }

        // Whatever the form no longer lists is revoked. The grant row goes; the account it minted
        // stays, because the tenant's audit log names it as the actor of what was done while the
        // access lasted — deleting it would blank that history.
        existing.values().forEach(gone -> {
            authorizationService.deleteById(gone.getId());
            log.info("Consultant {} authorization for tenant {} revoked; membership kept for audit.",
                    profileId, gone.getTenantId());
        });
    }

    /**
     * Create this consultant's membership of a company, unless they already hold one.
     *
     * <p>Runs inside the target tenant's context so the row lands under it — the same mechanism
     * admin provisioning uses, and the reason a bare {@code createOne} would not do: tenantId is
     * stamped from context, not from the object.
     *
     * <p>An existing membership blocks rather than converts. A person who is already staff at this
     * company is a case the PRD declines to define (§0.1), and quietly turning their employment into
     * a consultancy — or attaching a second one — would decide it by accident.
     */
    private void mintMembership(Long profileId, Long tenantId) {
        Optional<UserAccount> held = accountService.findMembershipInTenant(tenantId, profileId);
        if (held.isPresent()) {
            if (Boolean.TRUE.equals(held.get().getConsultant())) {
                return;   // already a consultant here: the grant was re-added, the account stands
            }
            throw new BusinessException("This person already has an account in that company, so "
                    + "they cannot be authorized as a consultant there.");
        }
        inTenantContext(tenantId, () -> {
            UserAccount account = new UserAccount();
            account.setProfileId(profileId);
            account.setConsultant(Boolean.TRUE);
            // ACTIVE because nothing about the membership itself is pending — there is no invitation
            // to accept and no password to set for it. Whether it may be ENTERED is the grant's
            // question, asked live; status is not where a consultant's access is decided.
            account.setStatus(AccountStatus.ACTIVE);
            accountService.createOne(account);
            return null;
        });
        log.info("Consultant {} granted access to tenant {} — membership minted.", profileId, tenantId);
    }

    private boolean isEnabled(Long profileId) {
        return findProfile(profileId).map(p -> Boolean.TRUE.equals(p.getActive())).orElse(false);
    }

    private Optional<ConsultantProfile> findProfile(Long profileId) {
        return this.searchOne(new Filters().eq(ConsultantProfile::getProfileId, profileId));
    }

    /** Inclusive on both ends — a grant is live on its start day and on its end day. */
    private boolean coversToday(ConsultantAuthorization a) {
        LocalDate now = today();
        return a.getStartDate() != null && a.getEndDate() != null
                && !now.isBefore(a.getStartDate()) && !now.isAfter(a.getEndDate());
    }

    private void validate(ConsultantAuthorization a) {
        if (a.getTenantId() == null || a.getStartDate() == null || a.getEndDate() == null) {
            throw new BusinessException("Every authorization needs a tenant, a start date and an end date.");
        }
        if (a.getEndDate().isBefore(a.getStartDate())) {
            throw new BusinessException("An authorization cannot end before it starts.");
        }
        // The company has to exist before a membership is minted under it. The picker only offers
        // real tenants, but the API took any number, and mintMembership would then create an
        // account inside a tenant context nothing else has ever heard of. Skipped without the
        // tenant directory — there is nothing to ask.
        if (tenantInfoService != null && tenantInfoService.getTenantName(a.getTenantId()) == null) {
            throw new BusinessException("No company exists with id " + a.getTenantId() + ".");
        }
    }
}
