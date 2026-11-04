package io.softa.starter.user.service.impl;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.entity.ConsultantAuthorization;
import io.softa.starter.user.entity.ConsultantProfile;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;

import static io.softa.framework.base.context.ContextUtils.inTenantContext;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.dto.ConsultantGrantDTO;
import io.softa.starter.user.dto.ConsultantProfileDTO;
import io.softa.starter.user.dto.ConsultantRowDTO;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserProfile;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;
import io.softa.starter.user.util.LoginIdentifiers;

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
    private UserIdentityService identityService;

    @Autowired
    private UserProfileService profileService;

    /** Only ever used to drop {@link ConsultantAccessCheckerImpl}'s per-membership answers — see
     *  {@link #forgetEntryAnswers}. Reached through the cache rather than through that bean, which
     *  depends on this service: a bean cycle here would fail the context outright. */
    @Autowired
    private CacheService cacheService;

    /** Optional: the list shows company names; absent tenant-starter → the id alone. */
    @Autowired(required = false)
    private TenantInfoService tenantInfoService;

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
        // The company's own state outranks the grant. A tenant the platform has frozen or
        // closed is not open to anyone, and a consultant is the one principal who would otherwise
        // walk straight in: their data access is unrestricted and their menus come from the plan, so
        // nothing further down would stop them. Absent tenant-starter there is no such state to
        // consult, and the grant alone decides.
        //
        // Asked first because it is the cheaper question (cached by the tenant directory) and rules
        // the rest out. The enabled check is grantStands' own — this used to ask it here as well, so
        // the per-request authorization check, which runs this on EVERY request a consultant makes,
        // read the consultant row twice.
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
    public List<ConsultantGrantDTO> grantsOf(Long profileId) {
        // One read. The grant names the membership it minted, and the status rides along as a
        // cascaded field, so the two halves of "may this consultant get in" arrive together instead
        // of being read separately and matched up in memory by (profileId, tenantId).
        return authorizationsOf(profileId).stream().map(grant -> {
            ConsultantGrantDTO row = new ConsultantGrantDTO();
            row.setId(grant.getId());
            row.setTenantId(grant.getTenantId());
            row.setTenantName(tenantInfoService == null ? null
                    : tenantInfoService.getTenantName(grant.getTenantId()));
            row.setEndDate(grant.getEndDate());
            row.setAccountStatus(grant.getAccountStatus());
            return row;
        }).toList();
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    @Transactional
    public Long save(ConsultantProfileDTO form) {
        Assert.notNull(form, "A consultant profile is required");
        String email = form.getEmail() == null ? null : form.getEmail().trim();
        String mobile = form.getMobile() == null ? null : form.getMobile().trim();
        // Both, the same rule the payload declares — repeated here because not every caller of the
        // service comes through the controller's validation.
        Assert.isTrue(email != null && !email.isEmpty(), "Please enter a valid email address");
        Assert.isTrue(mobile != null && !mobile.isEmpty(), "Mobile is required");

        Long profileId;
        boolean personIsNew;
        if (form.getProfileId() != null) {
            // A supplied id names a person who must exist. Without this a mistyped id made a
            // consultant record pointing at nobody — saveable, listable with blank columns, and
            // impossible to fix from the form, whose person lookups all come back empty.
            Assert.isTrue(profileService.getById(form.getProfileId()).isPresent(),
                    "No person exists with id {0}.", form.getProfileId());
            profileId = form.getProfileId();
            personIsNew = false;
        } else {
            Long byEmail = personHolding(email);
            Long byMobile = personHolding(mobile);
            // Already a consultant, reached from the CREATE form — refuse instead of editing them.
            // The form shows only the grants just typed, while the save applies the table as a whole
            // SET: going on would delete every grant this person already holds and close the
            // memberships behind them, with nothing on screen ever having named them. A person who
            // is merely an employee is fine to go on with — that is the one-person-two-hats case the
            // lookup exists for.
            //
            // Refused per channel, and the message names the channel. The operator typed two
            // identifiers and one of them belongs to a consultant; a sentence about "that person"
            // leaves them to guess which box to change, and the one guess they can act on is which
            // field it was. Email is asked first because it is looked up first below — a form that
            // collides on both gets the answer the person lookup would have picked.
            if (byEmail != null && findProfile(byEmail).isPresent()) {
                throw new BusinessException("This email is already used by another consultant profile.");
            }
            if (byMobile != null && findProfile(byMobile).isPresent()) {
                throw new BusinessException("This mobile is already used by another consultant profile.");
            }
            Long existing = byEmail != null ? byEmail : byMobile;
            personIsNew = existing == null;
            profileId = personIsNew
                    ? profileService.createPersonForJoin(email)
                    : existing;
        }
        applyBasicInformation(profileId, form.getUsername(), email, mobile,
                personIsNew || form.getProfileId() != null);

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

        applyAuthorizations(profileId, changesFrom(form.getAuthorizations(), personIsNew));
        return profileId;
    }

    /**
     * The form's authorization patch as the service's own changes.
     *
     * <p>A person being created has no grants yet, so naming one to re-date or revoke is a payload
     * that cannot be about anything. Refused by name rather than left to the ownership check, whose
     * message would say the id belongs to another consultant — true, and useless.
     */
    private ConsultantService.AuthorizationChanges changesFrom(
            ConsultantProfileDTO.AuthorizationPatch patch, boolean personIsNew) {
        if (patch == null) {
            return ConsultantService.AuthorizationChanges.none();
        }
        if (personIsNew) {
            Assert.isTrue(patch.getUpdate() == null || patch.getUpdate().isEmpty(),
                    "A consultant being created has no authorization to change.");
            Assert.isTrue(patch.getDelete() == null || patch.getDelete().isEmpty(),
                    "A consultant being created has no authorization to revoke.");
        }
        return new ConsultantService.AuthorizationChanges(
                rowsToGrants(patch.getCreate(), false),
                rowsToGrants(patch.getUpdate(), true),
                patch.getDelete());
    }

    /** Form rows as grant entities; {@code identified} rows must name the row they change. */
    private List<ConsultantAuthorization> rowsToGrants(List<ConsultantProfileDTO.AuthorizationRow> rows,
                                                       boolean identified) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(row -> {
            ConsultantAuthorization grant = new ConsultantAuthorization();
            if (identified) {
                Assert.notNull(row.getId(), "An authorization being changed must name which one.");
                grant.setId(row.getId());
            }
            grant.setTenantId(row.getTenantId());
            grant.setEndDate(row.getEndDate());
            return grant;
        }).toList();
    }

    /**
     * Write back what the form says about the PERSON — the half of this screen that is not about the
     * consultancy at all.
     *
     * <p><b>Written when the person is either just created or named by id; never when the save merely
     * FOUND them.</b> The three cases are not alike:
     *
     * <ul>
     *   <li><b>Just created</b> — the values are seeded rather than changed. Nothing is taken from
     *       anyone, and without them the consultant's name comes back as their email address, which
     *       is the identifier {@code createPersonForJoin} names them with.</li>
     *   <li><b>Named by id</b> — the edit form, used only by platform administrators. The operator
     *       opened this person's profile and is changing it on purpose, and the screen warns before
     *       saving that a login identifier is the person's across every company they belong to: it is
     *       one {@code UserIdentity}, resolved before any company is chosen.</li>
     *   <li><b>Found by lookup</b> — the create form, typing an address that already belongs to
     *       someone. Writing here was an account takeover with no exploit in it: the lookup matches on
     *       EITHER channel, so a victim's mobile submitted together with an attacker's email found the
     *       victim and moved their login address, after which a code login arrived as them. No id, no
     *       password, and the one check — that the new address belongs to nobody else — passes by
     *       construction, because the attacker owns it. It was also the misoperation: making an
     *       existing employee a consultant rewrote the number they sign in with everywhere, silently.
     *       Nobody in this case meant to change the person, so it writes nothing.</li>
     * </ul>
     *
     * <p>A blank field means "not supplied", never "clear it" — the form requires all three anyway.
     *
     * @param writesThePerson true for a person just created or named by id; false for one the save
     *                        found through the email / mobile lookup
     */
    private void applyBasicInformation(Long profileId, String username, String email, String mobile,
                                       boolean writesThePerson) {
        boolean changed = false;
        if (writesThePerson) {
            changed = renamePerson(profileId, username);
            changed |= moveLoginIdentifiers(profileId, email, mobile);
        }
        // AFTER the identifiers, not before: each consultant membership carries the login email as
        // the username and work email a customer's roster shows, so refreshing first would stamp the
        // address that was just replaced into every company. Also outside the change check entirely:
        // a membership minted before it carried these details has them empty for good, and nothing
        // about the person "changes" to trigger a refresh. Idempotent — it compares per membership
        // and writes only where something differs.
        refreshConsultantDisplay(profileId);

        if (changed) {
            // The cached UserInfo carries the name and the identifiers, and nothing evicts it on a
            // bare update — saveMyProfile does this by hand for the same reason. Keyed per
            // MEMBERSHIP, so every one of them has to go: miss one and that company serves the old
            // value until the entry expires a month later, to somebody just told the change was
            // saved. Once, after both writes, rather than once per write.
            accountService.listMembershipsOf(profileId)
                    .forEach(account -> profileService.evictUserInfo(account.getId()));
        }
    }

    /** @return true when the name was actually changed */
    private boolean renamePerson(Long profileId, String username) {
        String name = username == null ? null : username.trim();
        if (name == null || name.isEmpty()) {
            return false;
        }
        return profileService.getById(profileId)
                .filter(person -> !name.equals(person.getFullName()))
                .map(person -> {
                    person.setFullName(name);
                    profileService.updateOne(person);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Point the person's single {@code UserIdentity} at the form's email / mobile.
     *
     * <p>Canonical spelling, never what was typed. LoginIdentifiers is the one rule for a stored,
     * looked-up or hashed identifier, and everything that LOOKS a person up applies it — so a mobile
     * written here as "+65 9123-4567" is a row the login query, which asks for "+6591234567", cannot
     * find. The person then simply cannot sign in by mobile, and no migration rewrites such a row:
     * the class says so itself. The comparison is against the canonical form too, or an unchanged
     * number would be rewritten on every save.
     *
     * @return true when either identifier was actually changed
     */
    private boolean moveLoginIdentifiers(Long profileId, String email, String mobile) {
        String canonicalEmail = LoginIdentifiers.normalize(email);
        String canonicalMobile = LoginIdentifiers.normalize(mobile);
        return identityService.findByProfile(profileId).map(identity -> {
            // A patch carrying only what changed, not the row that was read: writing that back would
            // also put back whatever password it held, over one changed in the meantime.
            UserIdentity patch = new UserIdentity();
            patch.setId(identity.getId());
            boolean changed = false;
            if (canonicalEmail != null && !canonicalEmail.equals(identity.getLoginEmail())) {
                requireClaimable(canonicalEmail, profileId);
                identity.setLoginEmail(canonicalEmail);
                patch.setLoginEmail(canonicalEmail);
                changed = true;
            }
            if (canonicalMobile != null && !canonicalMobile.equals(identity.getLoginMobile())) {
                requireClaimable(canonicalMobile, profileId);
                identity.setLoginMobile(canonicalMobile);
                patch.setLoginMobile(canonicalMobile);
                changed = true;
            }
            if (changed) {
                identityService.updateOne(patch);
            }
            return changed;
        }).orElse(false);
    }

    private void requireClaimable(String identifier, Long profileId) {
        if (!identityService.isIdentifierClaimable(identifier, profileId)) {
            throw new BusinessException(
                    "\"" + identifier + "\" already belongs to someone else. Login identifiers are "
                            + "unique across the platform.");
        }
    }

    /**
     * The person who holds this login identifier, or {@code null} when nobody does.
     *
     * <p>Reusing an existing person is not a convenience, it is the only correct answer: login
     * identifiers are globally unique, so a second profile carrying this address cannot be created,
     * and the person who holds it IS the consultant being described. It is also what lets someone be
     * an employee at one company and a consultant for another — one person, two kinds of membership,
     * one picker. The caller asks per channel, because the operator may type whichever they know and
     * a refusal has to say which one it was about.
     *
     * <p>It never creates: whether a person was FOUND or MADE decides whether this screen may write
     * their credentials, so the caller has to be able to tell the two apart.
     */
    private Long personHolding(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        return identityService.findByLoginIdentifier(identifier)
                .map(UserIdentity::getProfileId)
                .orElse(null);
    }

    /**
     * The Consultant Profiles list.
     *
     * <p>Four reads for the whole page, not four per row. Each row shows the person's name, their
     * login identifiers and the companies whose grant covers today — three satellites of the
     * profile — and asking for them one consultant at a time made the list cost 3n+1 queries against
     * a platform-wide table with no upper bound on its size. The three satellites are fetched for
     * every consultant at once and matched up in memory.
     *
     * <p>The search still runs here rather than in the query, and that is not laziness: it matches
     * name and email, which live on {@code UserProfile} and {@code UserIdentity}, while the rows
     * being filtered are {@code ConsultantProfile}s. No single query spans the three, so the choice
     * is between filtering after the join or issuing the same three reads twice.
     */
    @SkipPermissionCheck
    @CrossTenant
    @Override
    public List<ConsultantRowDTO> list(String search) {
        String needle = search == null ? "" : search.trim().toLowerCase();
        // Newest first, like every other list in the product. Unordered, the page came back in
        // whatever order the database happened to return, so a consultant created a minute ago
        // could surface anywhere in it — and the row an operator is looking for right after
        // creating it is the one they just made.
        List<ConsultantProfile> profiles = this.searchList(
                new FlexQuery(new Filters(), Orders.ofDesc(ModelConstant.CREATED_TIME)));
        if (profiles.isEmpty()) {
            return List.of();
        }
        List<Long> profileIds = profiles.stream().map(ConsultantProfile::getProfileId)
                .filter(Objects::nonNull).distinct().toList();

        // The person, not their name: Collectors.toMap rejects a null VALUE, and a consultant
        // created through /join carries no full name until somebody types one.
        Map<Long, UserProfile> personByProfile = profileService
                .searchList(new Filters().in(UserProfile::getId, profileIds)).stream()
                .filter(person -> person.getId() != null)
                .collect(Collectors.toMap(UserProfile::getId, Function.identity(), (a, b) -> a));
        Map<Long, UserIdentity> identityByProfile = identityService
                .searchList(new Filters().in(UserIdentity::getProfileId, profileIds)).stream()
                .filter(identity -> identity.getProfileId() != null)
                .collect(Collectors.toMap(UserIdentity::getProfileId, Function.identity(), (a, b) -> a));
        Map<Long, List<ConsultantAuthorization>> grantsByProfile = authorizationService
                .searchList(new Filters().in(ConsultantAuthorization::getProfileId, profileIds)).stream()
                .filter(grant -> grant.getProfileId() != null)
                .collect(Collectors.groupingBy(ConsultantAuthorization::getProfileId));

        return profiles.stream()
                .map(profile -> toRow(profile, personByProfile, identityByProfile, grantsByProfile))
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
        // Bounded to the CALLER'S OWN tenant. The lookup answers for the actors on one page of one
        // tenant's audit log, and those are by definition memberships of that tenant — but the
        // method is @CrossTenant (the platform reads it too) and the endpoint is on the
        // authenticated-bypass list, so without this clause any signed-in user of any tenant could
        // post another tenant's account ids and harvest the login emails of its consultants. The
        // flag alone was harmless enough to miss; adding the email is what made the boundary matter.
        //
        // Null tenant = the platform's own read, which is allowed to span tenants.
        Long tenantId = ContextHolder.getContext() == null ? null : ContextHolder.getContext().getTenantId();
        Filters filters = new Filters()
                .in(UserAccount::getId, accountIds)
                .eq(UserAccount::getConsultant, true);
        if (tenantId != null) {
            filters = filters.eq(UserAccount::getTenantId, tenantId);
        }
        // Read straight from the accounts, bypassing the roster scope that hides consultants: the
        // tenant may not administer these memberships, but it must be able to attribute changes made
        // to its own data. The flag and the login email are exposed — the actor column names the
        // consultant by email so the tenant can tell WHICH consultant, and can quote it back to the
        // platform. Nothing else: no mobile, no grant dates, nothing about other customers.
        List<UserAccount> consultants = accountService.searchList(filters);
        if (consultants.isEmpty()) {
            return Map.of();
        }
        // One read for the page's consultants, not one per row.
        List<Long> profileIds = consultants.stream().map(UserAccount::getProfileId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> emailByProfile = profileIds.isEmpty() ? Map.of()
                : identityService.searchList(new Filters()
                                .in(UserIdentity::getProfileId, profileIds)).stream()
                        .filter(identity -> identity.getProfileId() != null)
                        .collect(Collectors.toMap(
                                UserIdentity::getProfileId,
                                identity -> identity.getLoginEmail() == null ? "" : identity.getLoginEmail(),
                                (a, b) -> a));
        Map<Long, String> out = new HashMap<>();
        for (UserAccount account : consultants) {
            String email = emailByProfile.get(account.getProfileId());
            out.put(account.getId(), email == null || email.isEmpty() ? null : email);
        }
        return out;
    }

    /** One list row, assembled from the page-wide satellite maps {@link #list} has already read. */
    private ConsultantRowDTO toRow(ConsultantProfile profile,
                                   Map<Long, UserProfile> personByProfile,
                                   Map<Long, UserIdentity> identityByProfile,
                                   Map<Long, List<ConsultantAuthorization>> grantsByProfile) {
        ConsultantRowDTO row = new ConsultantRowDTO();
        Long profileId = profile.getProfileId();
        row.setProfileId(profileId);
        row.setActive(profile.getActive());
        UserProfile person = personByProfile.get(profileId);
        row.setUsername(person == null ? null : person.getFullName());
        UserIdentity identity = identityByProfile.get(profileId);
        if (identity != null) {
            row.setEmail(identity.getLoginEmail());
            row.setMobile(identity.getLoginMobile());
        }
        // Live grants only — the badges must agree with the switcher the consultant will see. The
        // enabled check is answered from the row already in hand rather than by re-reading it.
        Set<Long> live = Boolean.TRUE.equals(profile.getActive())
                ? grantsByProfile.getOrDefault(profileId, List.of()).stream().filter(this::coversToday)
                        .map(ConsultantAuthorization::getTenantId).collect(Collectors.toSet())
                : Set.of();
        row.setAuthorizedTenants(live.stream().map(tenantId -> {
            ConsultantRowDTO.Tenant badge =
                    new ConsultantRowDTO.Tenant();
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
        forgetEntryAnswers(profileId);
        log.info("Consultant {} {}.", profileId, active ? "enabled" : "disabled");
    }

    @SkipPermissionCheck
    @CrossTenant
    @Override
    @Transactional
    public void applyAuthorizations(Long profileId, ConsultantService.AuthorizationChanges changes) {
        Assert.notNull(profileId, "profileId is required");
        ConsultantService.AuthorizationChanges patch =
                changes == null ? ConsultantService.AuthorizationChanges.none() : changes;

        // Validated before anything is read or written: a grant naming the platform tier, or no
        // company at all, is refused at the door rather than after a membership has been minted
        // for a row that is not going to exist.
        patch.created().forEach(this::validate);

        Map<Long, ConsultantAuthorization> held = authorizationsOf(profileId).stream()
                .collect(Collectors.toMap(ConsultantAuthorization::getId, Function.identity()));

        // Named rows must be this consultant's, and must still exist. Both failures mean the same
        // thing in practice — somebody else revoked the grant while this form was open — and it is
        // the only concurrent edit that can be detected at all, because a row that is gone leaves
        // nothing behind to compare against.
        Stream.concat(patch.updated().stream().map(ConsultantAuthorization::getId),
                        patch.revokedIds().stream())
                .filter(id -> !held.containsKey(id))
                .findFirst()
                .ifPresent(id -> {
                    throw new BusinessException(
                            "That authorization no longer exists — somebody else changed this "
                                    + "consultant while you had the page open. Refresh and try again.");
                });

        // One row per company: two grants for the same pair would make "is this live today?"
        // answerable two ways. Checked against what will be left standing, not against the payload
        // alone, so adding a company that is already authorized is refused here as a sentence
        // rather than at the unique index as a constraint naming a column.
        Set<Long> revoked = new HashSet<>(patch.revokedIds());
        Set<Long> companies = held.values().stream()
                .filter(grant -> !revoked.contains(grant.getId()))
                .map(ConsultantAuthorization::getTenantId)
                .collect(Collectors.toCollection(HashSet::new));
        patch.created().forEach(grant -> {
            if (!companies.add(grant.getTenantId())) {
                throw new BusinessException("That company is authorized twice — one grant per company.");
            }
        });

        for (ConsultantAuthorization fresh : patch.created()) {
            fresh.setProfileId(profileId);
            // Minted FIRST, so the grant can be written already pointing at it. The other order
            // needs a second write to fill the link in, and a failure between the two leaves a
            // grant that names no membership — the state the form calls "Missing".
            fresh.setAccountId(mintMembership(profileId, fresh.getTenantId()));
            authorizationService.createOne(fresh);
        }

        for (ConsultantAuthorization moved : patch.updated()) {
            ConsultantAuthorization stored = held.get(moved.getId());
            // Objects.equals, not a.equals(b): an open-ended grant carries no end date at all, so
            // both sides are legitimately null and reaching through one would answer an edit with a
            // NullPointerException. An update that turns out to change nothing is simply not
            // written — a form may send a row back untouched, and a no-op write would still stamp
            // the audit log with an edit nobody made.
            if (!Objects.equals(stored.getEndDate(), moved.getEndDate())) {
                stored.setEndDate(moved.getEndDate());
                // Nulls written, not skipped. The plain updateOne drops null fields, and a null end
                // date is the one value this edit exists to set — "No end" turns a dated grant into
                // an open-ended one. Skipped, it saved without error and left the old date in place.
                // Safe to write the whole row: it is the stored row just read, so every other column
                // goes back exactly as it was.
                authorizationService.updateOne(stored, false);
            }
        }

        // Whatever the operator removed is revoked: the grant row goes and the membership is
        // CLOSED, not deleted.
        //
        // Closed rather than deleted because the account is the actor this tenant's audit log points
        // at, and removing it blanks the authorship of everything the consultant did while they had
        // access. DEACTIVATED gets the clearing-out that asked for it anyway — listMembershipsOf
        // already hides those rows, so the consultant stops seeing the company and the tenant's
        // roster stops listing it, with no rule written for consultants specifically.
        //
        // It is also the only version that survives being re-authorized: (tenantId, profileId) is
        // unique, so a deleted-then-re-granted consultant would need a NEW account, and the tenant's
        // history of that person would split in two with no way to join them back up.
        for (Long revokedId : patch.revokedIds()) {
            ConsultantAuthorization gone = held.get(revokedId);
            authorizationService.deleteById(gone.getId());
            closeMembership(profileId, gone);
            log.info("Consultant {} authorization for tenant {} revoked; membership closed.",
                    profileId, gone.getTenantId());
        }

        backfillAccountLinks(profileId, held.values(), revoked);
        forgetEntryAnswers(profileId);
    }

    /**
     * Fill in the membership link on grants written before the grant row carried one.
     *
     * <p>Repaired on save rather than by a migration: the membership is findable from the pair
     * either way, and a grant that names no account reads on the form as one that minted nothing.
     *
     * <p>Run over every grant the consultant holds, not only the ones this save touched. The repair
     * used to ride along with the update of each row, which worked while the form resubmitted the
     * whole table on every save — now that it sends only what changed, a legacy row nobody edits
     * would never be reached.
     */
    private void backfillAccountLinks(Long profileId, Collection<ConsultantAuthorization> held,
                                      Set<Long> revoked) {
        held.stream()
                .filter(grant -> !revoked.contains(grant.getId()))
                .filter(grant -> grant.getAccountId() == null)
                .forEach(grant -> accountService.findMembershipInTenant(grant.getTenantId(), profileId)
                        .map(UserAccount::getId)
                        .ifPresent(accountId -> {
                            grant.setAccountId(accountId);
                            authorizationService.updateOne(grant);
                        }));
    }

    /**
     * Close the membership a revoked grant had minted, leaving the row and its history in place.
     *
     * <p>Only ever a consultant's own membership: this person may also be a genuine employee of that
     * company — the design says so explicitly — and revoking a consultancy must not touch the
     * employment. The flag is what tells the two apart.
     */
    private void closeMembership(Long profileId, ConsultantAuthorization gone) {
        // The grant names its membership, so there is nothing to look up — except for a grant
        // written before the link existed, which still has to be found by the pair.
        (gone.getAccountId() != null
                ? accountService.getById(gone.getAccountId())
                : accountService.findMembershipInTenant(gone.getTenantId(), profileId))
                .filter(account -> Boolean.TRUE.equals(account.getConsultant()))
                // Only a LIVE membership is closed. A row the customer suspended keeps their state:
                // revoking is the platform withdrawing its own yes, not an occasion to erase theirs.
                //
                // Overwriting it was a hole with two steps in it. Revoke turned FROZEN into
                // DEACTIVATED, and re-authorizing revives exactly that — so a platform operator
                // could undo a customer's suspension by revoking and re-granting, silently, with
                // nothing on either screen saying it had happened. The guard on the revival was
                // written to stop precisely that and was reached with the evidence already gone.
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
                .ifPresent(account -> {
                    account.setStatus(AccountStatus.DEACTIVATED);
                    accountService.updateOne(account);
                    // Evicted HERE, because after this write the row is invisible to the sweep that
                    // follows: forgetEntryAnswers reads listMembershipsOf, which hides DEACTIVATED
                    // rows on purpose. The one membership a revoke is actually about was therefore
                    // the one membership whose cached "yes" survived — for up to a minute, on the
                    // path taken when somebody has to be cut off now.
                    cacheService.clear(ConsultantAccessCheckerImpl.cacheKey(account.getId()));
                });
    }

    /**
     * Drop the gate's cached "may this membership still be entered" answers for this person.
     *
     * <p>Disabling a consultant and revoking a grant are decisions somebody takes, and they have to
     * bite on the next request rather than whenever a minute happens to be up — a consultant removed
     * because of an incident must stop working now. Expiry by date is the case with nothing to hook,
     * and the cache's short TTL is what bounds that one; see {@link ConsultantAccessCheckerImpl}.
     *
     * <p>Every membership, not just the ones whose grant changed: a save rewrites the whole
     * authorization table, and the cheap over-eviction costs one re-read each.
     *
     * <p>Every LIVE membership, to be exact — this reads {@code listMembershipsOf}, which hides
     * deactivated rows. A membership a revoke just closed is therefore not reachable from here, and
     * {@link #closeMembership} evicts it itself at the moment it writes the status.
     */
    private void forgetEntryAnswers(Long profileId) {
        accountService.listMembershipsOf(profileId).forEach(account ->
                cacheService.clear(ConsultantAccessCheckerImpl.cacheKey(account.getId())));
    }

    /**
     * Create this consultant's membership of a company, unless they already hold one.
     *
     * <p>Runs inside the target tenant's context so the row lands under it — the same mechanism
     * admin provisioning uses, and the reason a bare {@code createOne} would not do: tenantId is
     * stamped from context, not from the object.
     *
     * <p>An existing membership blocks rather than converts. A person who is already staff at this
     * company is a case the requirement declines to define, and quietly turning their employment into
     * a consultancy — or attaching a second one — would decide it by accident.
     */
    private Long mintMembership(Long profileId, Long tenantId) {
        Optional<UserAccount> held = accountService.findMembershipInTenant(tenantId, profileId);
        if (held.isPresent()) {
            if (Boolean.TRUE.equals(held.get().getConsultant())) {
                // Re-authorized. The membership is the same one, revived rather than replaced:
                // (tenantId, profileId) is unique, so a second row is not even insertable — and it
                // is the actor this tenant's audit log already points at, so a new one would split
                // one person's history into two identities with a gap between them.
                //
                // Revived only from the state REVOKING left it in. A membership the CUSTOMER
                // suspended stays suspended: re-authorizing is the platform answering its own
                // question, and it does not get to overturn the tenant's.
                UserAccount membership = held.get();
                if (membership.getStatus() == AccountStatus.DEACTIVATED) {
                    membership.setStatus(AccountStatus.ACTIVE);
                    accountService.updateOne(membership);
                    log.info("Consultant {} re-authorized for tenant {} — membership revived.",
                            profileId, tenantId);
                }
                return membership.getId();
            }
            throw new BusinessException("This person already has an account in that company, so "
                    + "they cannot be authorized as a consultant there.");
        }
        Long accountId = inTenantContext(tenantId, () -> {
            UserAccount account = new UserAccount();
            account.setProfileId(profileId);
            account.setConsultant(Boolean.TRUE);
            stampDisplayIdentity(account, profileId, tenantId);
            // ACTIVE because nothing about the membership itself is pending — there is no invitation
            // to accept and no password to set for it. Whether it may be ENTERED is the grant's
            // question, asked live; status is not where a consultant's access is decided.
            account.setStatus(AccountStatus.ACTIVE);
            return accountService.createOne(account);
        });
        log.info("Consultant {} granted access to tenant {} — membership minted.", profileId, tenantId);
        return accountId;
    }

    /**
     * What a tenant sees on a consultant's membership: who this is, and how to name them.
     *
     * <p>Every OTHER business column on the row is the tenant's own data about its own staff —
     * activation, security policy, work contacts HR typed — and the platform fills none of them. Left
     * at that, the roster shows a line of em dashes, and "you may suspend this" means nothing against
     * a record nobody can identify.
     *
     * <p><b>The email is written only when it is free in that company.</b> {@code UserAccount.email}
     * carries a {@code (tenantId, email)} unique index, and a consultant's address is a platform
     * login identifier that some unrelated employee of this customer may already hold as their work
     * contact. Writing it blindly would make THAT collision refuse the authorization — a consultant
     * blocked out of a company for a reason that has nothing to do with them. The name and username
     * carry no index and are always written, so the row is identifiable either way.
     *
     * @return true when anything changed, so a refresh can skip a write that would say nothing
     */
    private boolean stampDisplayIdentity(UserAccount account, Long profileId, Long tenantId) {
        String name = profileService.getById(profileId).map(UserProfile::getFullName).orElse(null);
        String loginEmail = identityService.findByProfile(profileId)
                .map(UserIdentity::getLoginEmail).orElse(null);

        boolean changed = false;
        if (name != null && !name.equals(account.getNickname())) {
            account.setNickname(name);
            changed = true;
        }
        if (loginEmail != null && !loginEmail.equals(account.getUsername())) {
            account.setUsername(loginEmail);
            changed = true;
        }
        if (loginEmail != null && !loginEmail.equals(account.getEmail())
                && workEmailIsFree(tenantId, loginEmail, account.getId())) {
            account.setEmail(loginEmail);
            changed = true;
        }
        return changed;
    }

    /** Whether this company's roster already has that address as somebody else's work contact. */
    private boolean workEmailIsFree(Long tenantId, String email, Long exceptAccountId) {
        return accountService.searchList(new Filters()
                        .eq(UserAccount::getTenantId, tenantId)
                        .eq(UserAccount::getEmail, email)).stream()
                .allMatch(other -> Objects.equals(other.getId(), exceptAccountId));
    }

    /**
     * Carry the person's current details onto the memberships the tenant reads.
     *
     * <p>Consultant memberships only. A person may also be genuinely employed somewhere, and the
     * contacts on THAT row are the employer's own data about their own staff — editing the person on
     * the platform's console must not reach into it.
     */
    private void refreshConsultantDisplay(Long profileId) {
        accountService.listMembershipsOf(profileId).stream()
                .filter(account -> Boolean.TRUE.equals(account.getConsultant()))
                .forEach(account -> {
                    if (stampDisplayIdentity(account, profileId, account.getTenantId())) {
                        accountService.updateOne(account);
                    }
                });
    }

    private boolean isEnabled(Long profileId) {
        return findProfile(profileId).map(p -> Boolean.TRUE.equals(p.getActive())).orElse(false);
    }

    private Optional<ConsultantProfile> findProfile(Long profileId) {
        return this.searchOne(new Filters().eq(ConsultantProfile::getProfileId, profileId));
    }

    /**
     * Whether this grant admits today.
     *
     * <p>Inclusive on its end day, and <b>open-ended when it carries no end date</b> — a grant with
     * no agreed finish is the common case, and the absence of a date means "until somebody says
     * otherwise", never "expired". Reading an empty end as expired would silently lock out every
     * consultant on an open engagement.
     *
     * <p>There is no start day to check: a grant admits from the moment it is saved.
     */
    private boolean coversToday(ConsultantAuthorization a) {
        return a.getEndDate() == null || !today().isAfter(a.getEndDate());
    }

    private void validate(ConsultantAuthorization a) {
        if (a.getTenantId() == null) {
            throw new BusinessException("Every authorization needs a company.");
        }
        // The platform's own tier is not a customer. It carries the operator's console and the
        // seeded reference data, and a consultant admitted into it would hold full data access
        // over the platform itself rather than over a company that asked for help.
        //
        // Guarded here and not only in the picker: the picker is a convenience, the endpoint took
        // any id, and -1 is the one id somebody would reach for by hand.
        if (BaseConstant.PLATFORM_TENANT_ID.equals(a.getTenantId())) {
            throw new BusinessException("The platform is not a company a consultant can be authorized into.");
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
