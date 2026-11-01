package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** The step a ticket opens. */
    enum Step {
        CHANGE_PASSWORD, NAME_NEW_MOBILE, CONFIRM_NEW_MOBILE
    }

    /** What a ticket stands for: this step, for this person signed into this account. */
    record Ticket(Step step, Long profileId, Long accountId, String mobile) {
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
        return this.issue(new Ticket(Step.CHANGE_PASSWORD, account.getProfileId(), account.getId(), null));
    }

    private void changePasswordAsSelf(String ticket, String newPassword) {
        UserAccount account = this.currentAccount();
        Ticket held = this.require(ticket, Step.CHANGE_PASSWORD, account);
        UserIdentity identity = this.identityOf(account);
        boolean wasLocked = identityService.isPasswordLocked(identity);
        // Strength rules, the lock and the failure count all live in setPassword: a new password
        // lifts the lock exactly as a forgot-password reset does.
        identityService.setPassword(identity.getId(), newPassword);
        this.spend(ticket);
        if (wasLocked) {
            history.record(LoginEvent.UNLOCKED, held.profileId(), account.getId(), account.getTenantId(),
                    null, null, null);
        }
        this.signOutOtherDevices(account.getProfileId());
        securityLog.record(securityLog.entry(SecurityAction.PASSWORD_CHANGED, this.source(), account, null));
    }

    private String verifyCurrentForMobileAsSelf(String method, String code, String password) {
        UserAccount account = this.currentAccount();
        UserIdentity identity = this.identityOf(account);
        if ("password".equalsIgnoreCase(method)) {
            this.checkPassword(identity, account, password);
        } else if ("code".equalsIgnoreCase(method)) {
            codeGuard.verify(this.loginMobileOf(identity), code);
        } else {
            throw new BusinessException("Verify with a code or with your password.");
        }
        return this.issue(new Ticket(Step.NAME_NEW_MOBILE, account.getProfileId(), account.getId(), null));
    }

    private String checkNewMobileAsSelf(String ticket, String newMobile) {
        UserAccount account = this.currentAccount();
        this.require(ticket, Step.NAME_NEW_MOBILE, account);
        String mobile = LoginIdentifiers.normalize(StringUtils.trimToNull(newMobile));
        if (mobile == null || mobile.contains("@")) {
            throw new BusinessException("Enter a mobile number with its country code.");
        }
        this.refuseUnusable(this.identityOf(account), mobile);
        // Sent before the ticket is spent: a refused send (too soon, too many today) leaves the
        // person on this step with a ticket that still works.
        this.sendCode(mobile);
        this.spend(ticket);
        return this.issue(new Ticket(Step.CONFIRM_NEW_MOBILE, account.getProfileId(), account.getId(), mobile));
    }

    private void resendNewMobileCodeAsSelf(String ticket) {
        Ticket held = this.require(ticket, Step.CONFIRM_NEW_MOBILE, this.currentAccount());
        this.sendCode(held.mobile());
    }

    private void confirmNewMobileAsSelf(String ticket, String code) {
        UserAccount account = this.currentAccount();
        Ticket held = this.require(ticket, Step.CONFIRM_NEW_MOBILE, account);
        codeGuard.verify(held.mobile(), code);
        UserIdentity identity = this.identityOf(account);
        // Asked again: the number may have gone to someone in the minutes since it was named.
        this.refuseUnusable(identity, held.mobile());
        // A dormant holder gives the number up now, once the new owner has proved they have it.
        boolean heldByOther = identityService.findByLoginIdentifier(held.mobile())
                .filter(other -> !Objects.equals(other.getProfileId(), identity.getProfileId()))
                .isPresent();
        if (heldByOther && !accountService.releaseDormantLoginMobile(held.mobile(), "Login mobile changed")) {
            throw this.mobileTaken();
        }
        String previous = identity.getLoginMobile();
        identity.setLoginMobile(held.mobile());
        identityService.updateOne(identity);
        // The account list shows and finds the number the person signs in with, in every company.
        accountService.followLoginMobile(identity.getProfileId());
        this.spend(ticket);
        this.signOutOtherDevices(account.getProfileId());
        UserSecurityLog row = securityLog.entry(SecurityAction.LOGIN_MOBILE_CHANGED, this.source(), account, null);
        row.setOldValue(ContactMasking.mobile(previous));
        row.setNewValue(ContactMasking.mobile(held.mobile()));
        securityLog.record(row);
    }

    /**
     * Why this number cannot become the person's login mobile: it already is, or it signs in someone
     * who is still in a company. A dormant holder does not block — their number is released on
     * confirm.
     */
    private void refuseUnusable(UserIdentity identity, String mobile) {
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

    /** Every other device of the person, in every company; the one making the change stays. */
    private void signOutOtherDevices(Long profileId) {
        sessionService.revokePerson(profileId, sessionService.currentSessionId(),
                SessionRevokeReason.KICKED_BY_OTHER_DEVICE);
    }

    private SecurityLogSource source() {
        return ClientInfo.current().isApp() ? SecurityLogSource.APP : SecurityLogSource.WEB;
    }

    /** Send a code the shared limits allow, keyed by the number as login keys it. */
    private void sendCode(String mobile) {
        String number = LoginIdentifiers.normalize(mobile);
        codeGuard.beforeSend(number);
        String code = RandomStringUtils.insecure().nextNumeric(VerificationCodeGuard.CODE_LENGTH);
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

    private String issue(Ticket ticket) {
        String token = RandomUtils.randomString(TICKET_BYTES);
        cacheService.save(ticketKey(token), String.join("|", ticket.step().name(),
                ticket.profileId().toString(), ticket.accountId().toString(),
                StringUtils.defaultString(ticket.mobile())), TICKET_TTL_SECONDS);
        return token;
    }

    /**
     * The live ticket for this step, issued to the person signed into this very account; refused
     * otherwise. Not spent here — a step that fails leaves it usable for another try.
     */
    private Ticket require(String token, Step step, UserAccount account) {
        String value = StringUtils.isBlank(token) ? null : cacheService.get(ticketKey(token));
        Ticket ticket = value == null ? null : parse(value);
        if (ticket == null || ticket.step() != step || !Objects.equals(ticket.profileId(), account.getProfileId())
                || !Objects.equals(ticket.accountId(), account.getId())) {
            throw AccountErrorCodes.refusal(AccountErrorCodes.TICKET_EXPIRED,
                    "This step has expired. Please start again.");
        }
        return ticket;
    }

    private void spend(String token) {
        cacheService.clear(ticketKey(token));
    }

    private static Ticket parse(String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 4) {
            return null;
        }
        try {
            return new Ticket(Step.valueOf(parts[0]), Long.valueOf(parts[1]), Long.valueOf(parts[2]),
                    StringUtils.trimToNull(parts[3]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
