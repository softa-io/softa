package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.SmsRequestMessage;
import io.softa.framework.base.security.EncryptUtils;
import io.softa.framework.base.utils.RandomUtils;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.starter.user.constant.AccountErrorCodes;
import io.softa.starter.user.dto.MembershipOption;
import io.softa.starter.user.dto.MyAccountSummary;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.enums.LoginEvent;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.enums.SecurityLogSource;
import io.softa.starter.user.service.LoginService;
import io.softa.starter.user.service.MyCredentialService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserSecurityLogService;
import io.softa.starter.user.service.UserSessionService;
import io.softa.starter.user.util.ClientInfo;
import io.softa.starter.user.util.LoginIdentifiers;

@Slf4j
@Service
public class MyCredentialServiceImpl implements MyCredentialService {

    /** How long one step's ticket lasts. */
    static final int TICKET_TTL_SECONDS = 600;
    private static final int TICKET_BYTES = 32;
    /** Same template and copy as the login code: one code, one message, whichever screen asked. */
    private static final String TEMPLATE_CODE = "user.verification-code";
    private static final int DAY_SECONDS = 86400;
    /**
     * Times a day one person may prove themselves to change their mobile. Each pass opens a ticket
     * that can send a code to a number of their choosing, so a password that never runs out of
     * passes would never run out of texts either.
     */
    static final int DAILY_VERIFY_LIMIT = 10;
    /**
     * Different numbers one person may name in a day. Naming one also says whether it signs someone
     * else in, so this bounds the probing as well as the texts; trying the same number again is free.
     */
    static final int DAILY_NEW_MOBILE_LIMIT = 5;
    /** A full international number: a plus, then 7 to 15 digits (E.164 allows at most 15). */
    private static final Pattern INTERNATIONAL_MOBILE = Pattern.compile("\\+[0-9]{7,15}");

    /** The step a ticket opens. */
    enum Step {
        CHANGE_PASSWORD, NAME_NEW_MOBILE, CONFIRM_NEW_MOBILE
    }

    /**
     * What a ticket stands for: this step, for this person signed into this account, until
     * {@code expiresAt} (epoch seconds) — kept so a ticket put back after a failed step keeps the
     * lifetime it had, rather than starting a new one.
     */
    record Ticket(Step step, Long profileId, Long accountId, String mobile, long expiresAt) {
    }

    @Autowired
    private UserAccountService accountService;

    @Autowired
    private UserIdentityService identityService;

    @Autowired
    private LoginService loginService;

    @Autowired
    private VerificationCodeGuard codeGuard;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private UserSessionService sessionService;

    @Autowired
    private UserSecurityLogService securityLog;

    @Autowired
    private LoginHistoryRecorder history;

    @Autowired
    private JoinMobileFormat mobileFormat;

    @Autowired(required = false)
    private TenantInfoService tenantInfoService;

    // Every entry point acts on the caller alone — the account the session signed in, and the person
    // and records behind it — so none of it depends on the caller's roles or data scope: an employee
    // who joined a minute ago holds no role and must still be able to use "Me". The reads and writes
    // run in a context with the permission check off, set explicitly (an annotation on a method this
    // class calls on itself would never reach the proxy); what keeps them to the caller is that every
    // record is found from the account id the session carries, never from the request.
    @Override
    public MyAccountSummary summary() {
        return asSelf(() -> this.summaryAsSelf());
    }

    @Override
    public void sendCurrentMobileCode() {
        asSelf(() -> {
            this.sendCurrentMobileCodeAsSelf();
            return null;
        });
    }

    @Override
    public String verifyCodeForPassword(String code) {
        return asSelf(() -> this.verifyCodeForPasswordAsSelf(code));
    }

    @Override
    @Transactional
    public void changePassword(String ticket, String newPassword) {
        asSelf(() -> {
            this.changePasswordAsSelf(ticket, newPassword);
            return null;
        });
    }

    @Override
    public String verifyCurrentForMobile(String method, String code, String password) {
        return asSelf(() -> this.verifyCurrentForMobileAsSelf(method, code, password));
    }

    @Override
    public String checkNewMobile(String ticket, String newMobile) {
        return asSelf(() -> this.checkNewMobileAsSelf(ticket, newMobile));
    }

    @Override
    public void resendNewMobileCode(String ticket) {
        asSelf(() -> {
            this.resendNewMobileCodeAsSelf(ticket);
            return null;
        });
    }

    @Override
    @Transactional
    public void confirmNewMobile(String ticket, String code) {
        asSelf(() -> {
            this.confirmNewMobileAsSelf(ticket, code);
            return null;
        });
    }

    private static <T> T asSelf(java.util.function.Supplier<T> action) {
        Context context = ContextHolder.cloneContext();
        context.setSkipPermissionCheck(true);
        return ContextHolder.callWith(context, action::get);
    }

    private MyAccountSummary summaryAsSelf() {
        UserAccount account = this.currentAccount();
        String loginMobile = identityService.findByProfile(account.getProfileId())
                .map(UserIdentity::getLoginMobile).orElse(null);
        List<MembershipOption> companies = loginService.myTenants(account.getId());
        MembershipOption here = companies.stream()
                .filter(option -> Objects.equals(option.accountId(), account.getId()))
                .findFirst().orElse(null);
        return new MyAccountSummary(
                mobileFormat.format(loginMobile).masked(),
                ContactMasking.email(account.getEmail()),
                tenantInfoService == null ? null : tenantInfoService.getTenantName(account.getTenantId()),
                companies.size(),
                here != null ? here.status() : account.getStatus());
    }

    private void sendCurrentMobileCodeAsSelf() {
        this.sendCode(this.loginMobileOf(this.currentIdentity()));
    }

    private String verifyCodeForPasswordAsSelf(String code) {
        UserAccount account = this.currentAccount();
        UserIdentity identity = this.identityOf(account);
        codeGuard.verify(this.loginMobileOf(identity), code);
        return this.issue(Step.CHANGE_PASSWORD, account, null);
    }

    private void changePasswordAsSelf(String ticket, String newPassword) {
        UserAccount account = this.currentAccount();
        this.spending(ticket, Step.CHANGE_PASSWORD, account, held -> {
            UserIdentity identity = this.identityOf(account);
            boolean wasLocked = identityService.isPasswordLocked(identity);
            // Strength rules, the lock and the failure count all live in setPassword: a new password
            // lifts the lock exactly as a forgot-password reset does.
            identityService.setPassword(identity.getId(), newPassword);
            if (wasLocked) {
                history.record(LoginEvent.UNLOCKED, held.profileId(), account.getId(), account.getTenantId(),
                        null, null, null);
            }
            securityLog.record(securityLog.entry(SecurityAction.PASSWORD_CHANGED, this.source(), account, null));
            this.signOutOtherDevicesAfterCommit(account.getProfileId());
            return null;
        });
    }

    private String verifyCurrentForMobileAsSelf(String method, String code, String password) {
        UserAccount account = this.currentAccount();
        UserIdentity identity = this.identityOf(account);
        // Counted before the proof is checked: a failed try is a try too.
        this.withinDailyLimit(dailyKey("verify", account.getProfileId()), DAILY_VERIFY_LIMIT);
        if ("password".equalsIgnoreCase(method)) {
            this.checkPassword(identity, account, password);
        } else if ("code".equalsIgnoreCase(method)) {
            codeGuard.verify(this.loginMobileOf(identity), code);
        } else {
            throw new BusinessException("Verify with a code or with your password.");
        }
        return this.issue(Step.NAME_NEW_MOBILE, account, null);
    }

    private String checkNewMobileAsSelf(String ticket, String newMobile) {
        UserAccount account = this.currentAccount();
        // A refused step — a malformed or taken number, a send refused as too soon — puts the
        // ticket back, so the person stays on this step and can try another number.
        return this.spending(ticket, Step.NAME_NEW_MOBILE, account, held -> {
            String mobile = LoginIdentifiers.normalize(StringUtils.trimToNull(newMobile));
            if (mobile == null || !INTERNATIONAL_MOBILE.matcher(mobile).matches()) {
                throw new BusinessException("Enter a mobile number with its country code, such as +65 9123 4567.");
            }
            this.countNewMobile(account.getProfileId(), mobile);
            this.refuseUnusable(this.identityOf(account), mobile);
            this.sendCode(mobile);
            return this.issue(Step.CONFIRM_NEW_MOBILE, account, mobile);
        });
    }

    /**
     * Counts a number toward the day's limit before anything is said about it, so a probe that ends
     * in "taken" is counted like any other. A number already named today is not counted again.
     */
    private void countNewMobile(Long profileId, String mobile) {
        // One atomic step, so two requests at once cannot both slip in as the fifth number.
        if (!cacheService.addToBoundedSet(dailyKey("new-mobile", profileId), mobile, DAILY_NEW_MOBILE_LIMIT, DAY_SECONDS)) {
            throw tooMany();
        }
    }

    private void withinDailyLimit(String key, int limit) {
        Long count = cacheService.increment(key, DAY_SECONDS);
        if (count != null && count > limit) {
            throw tooMany();
        }
    }

    private static String dailyKey(String what, Long profileId) {
        return "me:" + what + ":" + profileId;
    }

    private static BusinessException tooMany() {
        return AccountErrorCodes.refusal(AccountErrorCodes.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
    }

    private void resendNewMobileCodeAsSelf(String ticket) {
        Ticket held = this.require(ticket, Step.CONFIRM_NEW_MOBILE, this.currentAccount());
        this.sendCode(held.mobile());
    }

    private void confirmNewMobileAsSelf(String ticket, String code) {
        UserAccount account = this.currentAccount();
        this.spending(ticket, Step.CONFIRM_NEW_MOBILE, account, held -> {
            codeGuard.verify(held.mobile(), code);
            UserIdentity identity = this.identityOf(account);
            // Asked again: the number may have gone to someone in the minutes since it was named.
            // A dormant holder gives the number up now, once the new owner has proved they have it.
            if (this.refuseUnusable(identity, held.mobile())
                    && !accountService.releaseDormantLoginMobile(held.mobile(), "Login mobile changed")) {
                throw this.mobileTaken();
            }
            String previous = identity.getLoginMobile();
            identity.setLoginMobile(held.mobile());
            try {
                identityService.updateOne(identity);
            } catch (DuplicateKeyException e) {
                // Someone else confirmed the same number a moment earlier: theirs now.
                throw this.mobileTaken();
            }
            // The account list shows and finds the number the person signs in with, in every company.
            accountService.followLoginMobile(identity.getProfileId());
            UserSecurityLog row = securityLog.entry(SecurityAction.LOGIN_MOBILE_CHANGED, this.source(), account, null);
            row.setOldValue(mobileFormat.format(previous).masked());
            row.setNewValue(mobileFormat.format(held.mobile()).masked());
            securityLog.record(row);
            this.signOutOtherDevicesAfterCommit(account.getProfileId());
            return null;
        });
    }

    /**
     * Refuses a number that cannot become the person's login mobile: it already is, or it signs in
     * someone who is still in a company. A dormant holder does not block — their number is released
     * on confirm — and is what a {@code true} return means.
     */
    private boolean refuseUnusable(UserIdentity identity, String mobile) {
        if (mobile.equals(LoginIdentifiers.normalize(identity.getLoginMobile()))) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.MOBILE_UNCHANGED,
                    "This is already your login mobile. Enter a different number.");
        }
        boolean heldByOther = identityService.findByLoginIdentifier(mobile)
                .filter(other -> !Objects.equals(other.getProfileId(), identity.getProfileId()))
                .isPresent();
        if (heldByOther && !accountService.isDormantLoginMobile(mobile)) {
            throw this.mobileTaken();
        }
        return heldByOther;
    }

    private BusinessException mobileTaken() {
        return AccountErrorCodes.refusal(AccountErrorCodes.MOBILE_TAKEN,
                "This mobile number is already linked to another account.");
    }

    /**
     * The password as proof, on the same terms as signing in: refused while locked, a wrong one
     * counts toward the lock, and the warning names the attempts left as it nears.
     */
    private void checkPassword(UserIdentity identity, UserAccount account, String password) {
        if (identityService.isPasswordLocked(identity)) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.PASSWORD_LOCKED, LoginServiceImpl.lockedMessage());
        }
        if (!identityService.matchesPassword(identity, password)) {
            long failures = identityService.recordPasswordFailure(identity);
            if (failures >= UserIdentityService.FAILURES_BEFORE_LOCK) {
                history.record(LoginEvent.LOCKED, identity.getProfileId(), account.getId(), account.getTenantId(),
                        null, null, null);
                throw AccountErrorCodes.refusal(AccountErrorCodes.PASSWORD_LOCKED, LoginServiceImpl.lockedMessage());
            }
            throw LoginServiceImpl.wrongPassword(failures);
        }
        identityService.clearPasswordFailures(identity.getId());
    }

    /**
     * Every other device of the person, in every company; the one making the change stays. Only
     * once the change is committed: a change that rolls back must not have signed anyone out.
     */
    private void signOutOtherDevicesAfterCommit(Long profileId) {
        String keep = sessionService.currentSessionId();
        Runnable signOut = () -> {
            try {
                sessionService.revokePerson(profileId, keep, SessionRevokeReason.KICKED_BY_OTHER_DEVICE);
            } catch (RuntimeException e) {
                // The change is committed and this device's caller must hear so: a failure to sign
                // the others out is logged, not turned into an error for a change that took effect.
                log.error("Other devices of profile {} not signed out after a credential change: {}",
                        profileId, e.getMessage());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            signOut.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                signOut.run();
            }
        });
    }

    private SecurityLogSource source() {
        return ClientInfo.current().isApp() ? SecurityLogSource.APP : SecurityLogSource.WEB;
    }

    /** Send a code the shared limits allow, keyed by the number as login keys it. */
    private void sendCode(String mobile) {
        String number = LoginIdentifiers.normalize(mobile);
        codeGuard.beforeSend(number);
        String code = RandomStringUtils.secure().nextNumeric(VerificationCodeGuard.CODE_LENGTH);
        codeGuard.store(number, code);
        eventPublisher.publishEvent(new SmsRequestMessage(List.of(number), TEMPLATE_CODE,
                Map.of("code", code, "expiryMinutes", VerificationCodeGuard.CODE_TTL_SECONDS / 60)));
    }

    private String loginMobileOf(UserIdentity identity) {
        String mobile = LoginIdentifiers.normalize(identity.getLoginMobile());
        if (mobile == null) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.MOBILE_NOT_SET,
                    "You have no login mobile to send a code to. Please contact your HR.");
        }
        return mobile;
    }

    private UserAccount currentAccount() {
        Long accountId = ContextHolder.getContext().getUserId();
        UserAccount account = accountId == null ? null : accountService.getById(accountId).orElse(null);
        if (account == null || account.getProfileId() == null) {
            throw new BusinessException("Current user not found.");
        }
        return account;
    }

    private UserIdentity currentIdentity() {
        return this.identityOf(this.currentAccount());
    }

    private UserIdentity identityOf(UserAccount account) {
        return identityService.findByProfile(account.getProfileId())
                .orElseThrow(() -> new BusinessException("Credentials not found."));
    }

    // ── tickets ─────────────────────────────────────────────────────

    private static String ticketKey(String ticket) {
        // Stored under its digest: a cache dump yields nothing a caller could present.
        return "me:ticket:" + EncryptUtils.computeSha256(ticket);
    }

    private String issue(Step step, UserAccount account, String mobile) {
        return this.store(RandomUtils.randomString(TICKET_BYTES), new Ticket(step, account.getProfileId(),
                account.getId(), mobile, nowSeconds() + TICKET_TTL_SECONDS));
    }

    private String store(String token, Ticket ticket) {
        long left = ticket.expiresAt() - nowSeconds();
        if (left > 0) {
            cacheService.save(ticketKey(token), String.join("|", ticket.step().name(),
                    ticket.profileId().toString(), ticket.accountId().toString(),
                    StringUtils.defaultString(ticket.mobile()), Long.toString(ticket.expiresAt())), (int) left);
        }
        return token;
    }

    /**
     * Spends the ticket on one step. It is taken out of the cache in one atomic read-and-delete, so
     * two requests presenting it at once cannot both get through; if the step then fails, it is put
     * back with the lifetime it had left, and the person can try again on the same screen.
     */
    private <T> T spending(String token, Step step, UserAccount account, Function<Ticket, T> work) {
        Ticket ticket = this.held(token, step, account, true);
        try {
            return work.apply(ticket);
        } catch (RuntimeException e) {
            this.store(token, ticket);
            throw e;
        }
    }

    /**
     * The live ticket for this step, issued to the person signed into this very account; refused
     * otherwise. Read only, for a step that does not move the person on (resending the code).
     */
    private Ticket require(String token, Step step, UserAccount account) {
        return this.held(token, step, account, false);
    }

    /**
     * The ticket for this step, issued to the person signed into this very account — read, or taken
     * out of the cache when {@code take}. Refused otherwise; one taken by someone it was not issued
     * to, or at the wrong step, is put back for its owner.
     */
    private Ticket held(String token, Step step, UserAccount account, boolean take) {
        String key = StringUtils.isBlank(token) ? null : ticketKey(token);
        String value = key == null ? null : take ? cacheService.getAndClear(key) : cacheService.get(key);
        Ticket ticket = value == null ? null : parse(value);
        if (ticket == null) {
            throw ticketExpired();
        }
        boolean theirs = ticket.step() == step && Objects.equals(ticket.profileId(), account.getProfileId())
                && Objects.equals(ticket.accountId(), account.getId());
        if (!theirs) {
            if (take) {
                this.store(token, ticket);
            }
            throw ticketExpired();
        }
        return ticket;
    }

    private static BusinessException ticketExpired() {
        return AccountErrorCodes.refusal(AccountErrorCodes.TICKET_EXPIRED, "This step has expired. Please start again.");
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    private static Ticket parse(String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 5) {
            return null;
        }
        try {
            return new Ticket(Step.valueOf(parts[0]), Long.valueOf(parts[1]), Long.valueOf(parts[2]),
                    StringUtils.trimToNull(parts[3]), Long.parseLong(parts[4]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
