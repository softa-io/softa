package io.softa.starter.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.utils.CookieUtils;
import io.softa.starter.user.dto.ChangePasswordDTO;
import io.softa.starter.user.dto.UserAccountDTO;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.service.UserAccountService;

/**
 * UserAccount Controller
 */
@Tag(name = "UserAccount Controller")
@RestController
@RequestMapping("/UserAccount")
public class UserAccountController extends EntityController<UserAccountService, UserAccount, String> {

    private static final Logger log = LoggerFactory.getLogger(UserAccountController.class);

    @Autowired
    private CacheService cacheService;

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = CookieUtils.getCookie(request, BaseConstant.SESSION_ID);
        cacheService.clear(RedisConstant.SESSION + sessionId);
        CookieUtils.clearCookie(response, BaseConstant.SESSION_ID);
        return ApiResponse.success();
    }

    @Operation(summary = "changeMyPassword")
    @PostMapping("/changeMyPassword")
    public ApiResponse<Void> changeMyPassword(@RequestBody @Valid ChangePasswordDTO changePasswordDTO) {
        service.changeMyPassword(changePasswordDTO.getCurrentPassword(), changePasswordDTO.getNewPassword());
        return ApiResponse.success();
    }

    @Operation(summary = "getMyAccount")
    @GetMapping("/getMyAccount")
    public ApiResponse<UserAccount> getMyAccount() {
        String userId = ContextHolder.getContext().getUserId();
        try {
            Optional<UserAccount> accountOpt = service.getById(userId);

            if (accountOpt.isEmpty()) {
                log.warn("Current user account not found for ID: {}", userId);
                return new ApiResponse<>(ResponseCode.USER_NOT_FOUND.getCode(), "Current user account not found.",
                        null);
            }
            UserAccount account = accountOpt.get();
            // Mask sensitive fields before returning
            account.setPassword(null);
            account.setPasswordSalt(null);
            return ApiResponse.success(account);
        } catch (Exception e) {
            log.error("Error fetching current user account for ID: {}", userId, e);
            return new ApiResponse<>(ResponseCode.ERROR.getCode(), "Failed to retrieve user account.", null);
        }
    }

    @Operation(summary = "saveMyAccount")
    @PostMapping("/saveMyAccount")
    public ApiResponse<Void> saveMyAccount(@RequestBody @Valid UserAccountDTO myAccountDTO) {
        String currentUserId;
        try {
            currentUserId = ContextHolder.getContext().getUserId();
            if (currentUserId == null) {
                log.warn("Attempt to save current account without authenticated context.");
                return new ApiResponse<>(ResponseCode.UNAUTHORIZED.getCode(), "User not authenticated.", null);
            }
        } catch (Exception e) {
            log.error("Error retrieving user ID from context", e);
            return new ApiResponse<>(ResponseCode.ERROR.getCode(), "Could not determine current user.", null);
        }

        try {
            UserAccount existingAccount = service.getById(currentUserId)
                    .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND,
                            "Current user account not found for update."));

            existingAccount.setNickname(myAccountDTO.getNickname());
            existingAccount.setEmail(myAccountDTO.getEmail());
            existingAccount.setMobile(myAccountDTO.getMobile());

            boolean success = service.updateOne(existingAccount);

            if (success) {
                log.info("User account updated successfully for user ID: {}", currentUserId);
                return ApiResponse.success();
            } else {
                log.error("Failed to update user account for user ID: {}. updateOne returned false.", currentUserId);
                return new ApiResponse<>(ResponseCode.ERROR.getCode(), "Failed to update user account.", null);
            }
        } catch (BusinessException be) {
            log.warn("BusinessException while saving account for user ID {}: {}", currentUserId, be.getMessage());
            return new ApiResponse<>(be.getResponseCode() != null ? be.getResponseCode().getCode()
                    : ResponseCode.BUSINESS_EXCEPTION.getCode(), be.getMessage(), null);
        } catch (Exception e) {
            log.error("Error saving current user account for ID: {}", currentUserId, e);
            return new ApiResponse<>(ResponseCode.ERROR.getCode(), "Failed to save user account.", null);
        }
    }
}