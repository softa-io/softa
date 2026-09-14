package io.softa.starter.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.enums.SystemUser;
import io.softa.framework.base.exception.UserNotFoundException;
import io.softa.framework.orm.annotation.SwitchUser;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.utils.CookieUtils;
import io.softa.starter.user.dto.*;
import io.softa.starter.user.service.LoginService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserProfileService;
import io.softa.starter.user.service.OAuth2Service;

/**
 * Login Controller
 * login, register, forget password, reset password, force-reset password
 *
 * <p>Every token these endpoints take (pre-auth, invitation) travels in the request body, never in
 * the URL: a query string is copied into access logs, proxies and browser history, and each of
 * these tokens mints a session or binds a person — the invite link itself is the one unavoidable
 * URL carrier, and the follow-up calls must not repeat it.
 */
@Slf4j
@Tag(name = "Login")
@RestController
@RequestMapping("/login")
public class LoginController {

    @Autowired
    private LoginService loginService;

    /** The /join endpoints delegate the token work to the invitation service. */
    @Autowired
    private UserInvitationService invitationService;

    /** Builds the session payload once a membership is chosen. */
    @Autowired
    private UserProfileService profileService;

    @Autowired
    private OAuth2Service oAuth2Service;

    /** Reads the session behind an authenticated call, and drops the one a switch replaces. */
    @Autowired
    private CacheService cacheService;

    /**
     * Login by Apple ID
     * Set cookie with session id
     */
    @PostMapping("/loginByApple")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<UserInfo> loginByApple(@RequestBody @Valid AppleLoginDTO appleLoginDTO,
                    HttpServletResponse response) {
        UserInfo userInfo = oAuth2Service.loginByApple(appleLoginDTO.getToken());
        String sessionId = loginService.generateSessionId(userInfo.getUserId());
        CookieUtils.setCookie(response, BaseConstant.SESSION_ID, sessionId);
        return ApiResponse.success(userInfo);
    }

    /**
     * Login by OAuth2
     * Set cookie with session id
     */
    @PostMapping("/loginByOAuth2")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<UserInfo> loginByOAuth2(@RequestBody @Valid OAuthCredential oAuthCredential,
            HttpServletResponse response) {
        UserInfo userInfo = oAuth2Service.loginByOAuth2(oAuthCredential);
        String sessionId = loginService.generateSessionId(userInfo.getUserId());
        CookieUtils.setCookie(response, BaseConstant.SESSION_ID, sessionId);
        return ApiResponse.success(userInfo);
    }

    /**
     * Login by email verification code
     * Set cookie with session id
     */
    @PostMapping("/loginByEmailCode")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<AuthenticationResult> loginByEmail(@RequestBody @Valid EmailCodeDTO emailCodeDTO,
            HttpServletResponse response) {
        return this.issueOrAskForCompany(
                loginService.authenticateByCode(emailCodeDTO.getEmail(), emailCodeDTO.getCode()), response);
    }

    /**
     * Login by mobile verification code
     * Set cookie with session id
     */
    @PostMapping("/loginByMobileCode")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<AuthenticationResult> loginByMobileCode(@RequestBody @Valid MobileCodeDTO mobileCodeDTO,
            HttpServletResponse response) {
        return this.issueOrAskForCompany(
                loginService.authenticateByCode(mobileCodeDTO.getMobile(), mobileCodeDTO.getCode()), response);
    }

    @PostMapping("/sendEmailCode")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<Void> sendEmailCode(@RequestBody @Valid SendEmailCodeDTO body) {
        loginService.sendEmailCode(body.getEmail());
        return ApiResponse.success();
    }

    @PostMapping("/sendMobileCode")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<Void> sendMobileCode(@RequestBody @Valid SendMobileCodeDTO body) {
        loginService.sendMobileCode(body.getMobile());
        return ApiResponse.success();
    }


    /**
     * Login by identifier (login email or login mobile) and password
     * Set cookie with session id
     */
    @PostMapping("/loginByPassword")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<AuthenticationResult> loginByPassword(@RequestBody @Valid IdentifierPasswordDTO dto,
            HttpServletResponse response) {
        return this.issueOrAskForCompany(
                loginService.authenticateByPassword(dto.getIdentifier(), dto.getPassword()), response);
    }

    /**
     * The /join landing check — may this token proceed, and if not, why (PRD §3.0).
     *
     * <p>Public by necessity: whoever opened the link has no session yet. It reveals only masked
     * contacts and the company name, so a leaked token yields recognition, not usable details.
     */
    @Operation(summary = "Check an invitation link and return what the join page should show")
    @PostMapping("/joinEntry")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<JoinEntry> joinEntry(@RequestBody @Valid JoinTokenDTO dto) {
        return ApiResponse.success(invitationService.inspectJoinToken(dto.getToken()));
    }

    /**
     * Send a verification code to the channel the invitation names, without the caller ever seeing
     * the address. The join page only has masked contacts, so it cannot use the plaintext
     * send-code endpoints — and it must not, or a leaked link would become an address oracle.
     */
    @Operation(summary = "Send a verification code to the invitation's own email or mobile")
    @PostMapping("/sendJoinCode")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<Void> sendJoinCode(@RequestBody @Valid JoinCodeRequestDTO dto) {
        loginService.sendJoinCode(dto.getToken(), dto.getChannel());
        return ApiResponse.success();
    }

    /**
     * Verify the code and identify the person, without issuing a session. Joining is a separate
     * agreement — see confirmJoin — so this step deliberately stops at "we know who you are".
     */
    @Operation(summary = "Verify the join code and return the person behind the invitation")
    @PostMapping("/verifyJoinCode")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<JoinVerification> verifyJoinCode(@RequestBody @Valid VerifyJoinCodeDTO dto) {
        return ApiResponse.success(loginService.verifyJoinCode(dto.getToken(), dto.getChannel(), dto.getCode()));
    }

    /**
     * Set a first password mid-join, where no session exists yet. Authorized by the invitation, and
     * narrow because of it: it reaches only the profile the invitation names, only when that
     * profile has no password.
     */
    @Operation(summary = "Set the first password during the join flow")
    @PostMapping("/setJoinPassword")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<Void> setJoinPassword(@RequestBody @Valid SetJoinPasswordDTO dto) {
        loginService.setJoinPassword(dto.getToken(), dto.getProfileId(), dto.getNewPassword(), dto.getProof());
        return ApiResponse.success();
    }

    /**
     * Confirm joining, after the person verified their identity (and set a password if new).
     *
     * <p>A session is issued here, not earlier: activation and "you are now in" are the same
     * moment. Verifying a code proves control of the invitation; joining is the agreement. The
     * service may also answer {@code signInRequired} — joined, but no session — and that result
     * carries no userInfo, so it passes through issueOrAskForCompany without a cookie.
     */
    @Operation(summary = "Accept the invitation: bind the person, activate the membership, sign in")
    @PostMapping("/confirmJoin")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<AuthenticationResult> confirmJoin(@RequestBody @Valid ConfirmJoinDTO dto,
            HttpServletResponse response) {
        // Re-runs the company resolution rather than assuming the just-joined membership is the
        // only one: the person may already belong elsewhere, in which case they must still choose.
        return this.issueOrAskForCompany(
                loginService.confirmJoin(dto.getToken(), dto.getProfileId(), dto.getProof()), response);
    }

    /**
     * Issue the session when authentication resolved to one membership; otherwise hand back the
     * choice for the client to present.
     *
     * <p>Shared by all three authentication endpoints so the "one or many" decision cannot differ
     * between channels — a path that issued a session without going through here would bypass the
     * whole point of the company step.
     *
     * <p>The response carries {@code profileId} either way: the client needs it for
     * {@code selectTenant}, and it is also what makes {@code mustSetPassword} actionable.
     */
    private ApiResponse<AuthenticationResult> issueOrAskForCompany(AuthenticationResult result,
            HttpServletResponse response) {
        if (result.isResolved()) {
            String sessionId = loginService.generateSessionId(result.userInfo().getUserId());
            CookieUtils.setCookie(response, BaseConstant.SESSION_ID, sessionId);
        }
        return ApiResponse.success(result);
    }

    /**
     * The tenants an authenticated person may enter.
     *
     * <p>Reached when authentication succeeded but the person belongs to more than one company
     * (or to one they cannot enter), so no session was issued yet. Not tenant-scoped by nature —
     * the caller has proven who they are but not yet chosen where they are going.
     */
    @Operation(summary = "List the tenants this person can log into (multi-company login step)")
    @PostMapping("/listTenants")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<List<MembershipOption>> listTenants(@RequestBody @Valid AuthTokenDTO dto) {
        return ApiResponse.success(loginService.listTenants(dto.getAuthToken()));
    }

    /**
     * Enter one company, issuing the session.
     *
     * <p>The service verifies the membership really belongs to this person before returning its
     * account id — naming someone else's would otherwise mint a session in a company the caller
     * is not a member of.
     */
    @Operation(summary = "Enter the chosen company and issue the session")
    @PostMapping("/selectTenant")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<AuthenticationResult> selectTenant(@RequestBody @Valid SelectTenantDTO dto,
            HttpServletResponse response) {
        // The person is read from the token inside the service, never from the request. Same
        // response shape as the authentication endpoints, so the client has one contract to handle.
        return this.issueOrAskForCompany(
                loginService.selectTenant(dto.getAuthToken(), dto.getAccountId()), response);
    }

    /**
     * The tenants the CURRENT session's person may enter — what the header's tenant switcher lists.
     *
     * <p>Same options as the login picker, including the company they are in now, so the switcher
     * can show the current one selected and badge the rest exactly as the picker does.
     */
    @Operation(summary = "List the tenants the signed-in person can switch to")
    @GetMapping("/myTenants")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<List<MembershipOption>> myTenants(HttpServletRequest request) {
        return ApiResponse.success(loginService.myTenants(this.sessionUser(this.sessionIdOf(request))));
    }

    /**
     * Move the session to another of this person's tenants.
     *
     * <p>Authorized by the CURRENT SESSION, not by a pre-auth token: the caller is already signed
     * in, and a second token-shaped route to minting a session is exactly what this endpoint must
     * not become. The membership must be one the session's person holds and must be selectable —
     * {@code switchTenant} makes both checks — and {@code generateSessionId} then re-runs the same
     * tenant and account gates login runs, against the TARGET membership.
     *
     * <p>A NEW session id rather than a rewrite of the old mapping: the session maps to a
     * UserAccount id, and every downstream layer (ContextBuilder, the permission snapshot, data
     * scope) is keyed off it, so repointing it in place would leave warmed caches and any in-flight
     * request pointing at the previous company.
     */
    @Operation(summary = "Switch the signed-in session to another of this person's tenants")
    @PostMapping("/switchTenant")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<AuthenticationResult> switchTenant(@RequestBody @Valid SwitchTenantDTO dto,
            HttpServletRequest request, HttpServletResponse response) {
        String previousSessionId = this.sessionIdOf(request);
        AuthenticationResult result =
                loginService.switchTenant(this.sessionUser(previousSessionId), dto.getAccountId());
        // Gates first: a refusal here mints nothing, and the caller is still in the company they
        // started in — which is why the old session is dropped only after this returns.
        String sessionId = loginService.generateSessionId(result.userInfo().getUserId());
        // Explicit, and before the new cookie goes out: a copy of the old cookie taken from another
        // device would otherwise keep the previous company alive alongside the new one.
        cacheService.clear(RedisConstant.SESSION + previousSessionId);
        CookieUtils.setCookie(response, BaseConstant.SESSION_ID, sessionId);
        return ApiResponse.success(result);
    }

    /**
     * Leave the tenant this session is in and return to the company step, still authenticated
     * The client calls this when a request inside a tenant answered 414 — the
     * consultant's authorization there ended — instead of signing the person out.
     *
     * <p>Authorized by the CURRENT SESSION, like {@link #switchTenant}. The session is dropped only
     * after the service has answered, so a refusal (nothing left to enter) leaves the caller
     * where they were, and the cookie goes with it: the browser must not carry a session id that
     * maps to nothing, or the login page would probe it and read the 401 as a sign-out.
     *
     * <p>No new session is minted. The answer carries a single-use pre-auth token, and the picker
     * spends it on {@code selectTenant} exactly as after a fresh login — one route to a session,
     * not two.
     */
    @Operation(summary = "Leave the current tenant and return to the company step, still authenticated")
    @PostMapping("/leaveTenant")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<AuthenticationResult> leaveTenant(HttpServletRequest request,
                                                         HttpServletResponse response) {
        String sessionId = this.sessionIdOf(request);
        AuthenticationResult result = loginService.leaveTenant(this.sessionUser(sessionId));
        cacheService.clear(RedisConstant.SESSION + sessionId);
        CookieUtils.clearCookie(response, BaseConstant.SESSION_ID);
        return ApiResponse.success(result);
    }

    /**
     * The session id this request carries — cookie first, then the header {@code ContextBuilder}
     * also accepts, so the two endpoints above authenticate the same way every other endpoint does.
     *
     * <p>Read here rather than taken from the Context on purpose. {@code /login/**} is anonymous at
     * the context filter and public in the permission gate, so an UNAUTHENTICATED caller reaches
     * these handlers and the bound Context carries no userId at all. Assuming the framework had
     * gated them would make both endpoints answer for whoever asked.
     */
    private String sessionIdOf(HttpServletRequest request) {
        String sessionId = CookieUtils.getCookie(request, BaseConstant.SESSION_ID);
        if (sessionId == null) {
            sessionId = request.getHeader(BaseConstant.SESSION_ID_HEADER);
        }
        if (sessionId == null) {
            throw new UserNotFoundException("Session ID is missing");
        }
        return sessionId;
    }

    /** The UserAccount — one membership — the session maps to; absent means expired or forged. */
    private Long sessionUser(String sessionId) {
        Long userId = cacheService.get(RedisConstant.SESSION + sessionId, Long.class);
        if (userId == null) {
            throw new UserNotFoundException("Invalid session ID");
        }
        return userId;
    }

    /**
     * Forgot password, send reset password email
     */
    @PostMapping("/forgetPassword")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<Void> forgotPassword(@RequestBody @Valid ForgotPasswordDTO forgotPasswordDTO) {
        loginService.forgetPassword(forgotPasswordDTO.getEmail());
        return ApiResponse.success();
    }

    /**
     * Reset password using the token sent via email
     */
    @Operation(summary = "Reset a password with a verification code")
    @PostMapping("/resetPasswordByCode")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<Void> resetPasswordByCode(@RequestBody @Valid ResetPasswordByCodeDTO dto) {
        loginService.resetPasswordByCode(dto.getIdentifier(), dto.getCode(), dto.getNewPassword());
        return ApiResponse.success();
    }

    @PostMapping("/resetPassword")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<Void> resetPassword(@RequestBody @Valid ResetPasswordDTO resetPasswordDTO) {
        loginService.resetPassword(resetPasswordDTO.getToken(), resetPasswordDTO.getNewPassword());
        return ApiResponse.success();
    }

    /**
     * Validate an invitation / reset token for the public set-password page (greets the holder with
     * the email; never reveals why an invalid token failed).
     */
    @PostMapping("/inviteInfo")
    @SwitchUser(SystemUser.REGISTERED_USER)
    public ApiResponse<InvitationInfo> inviteInfo(@RequestBody @Valid InviteInfoDTO body) {
        return ApiResponse.success(loginService.inviteInfo(body.getToken()));
    }

}