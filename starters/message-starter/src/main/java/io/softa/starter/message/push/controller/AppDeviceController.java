package io.softa.starter.message.push.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.starter.message.push.service.AppPushDeviceService;
import io.softa.framework.web.response.ApiResponse;

/**
 * Push-device registration. The APP registers after signing in, after switching company and when its
 * push token changes; it drops the registration when the person turns notifications off. Signing out
 * drops it through /UserAccount/logout.
 */
@Tag(name = "App Devices")
@RestController
@RequestMapping("/app/devices")
@RequiredArgsConstructor
public class AppDeviceController {

    private final AppPushDeviceService deviceService;

    /** {@code platform} is {@code ios} or {@code android}. */
    public record RegisterBody(String platform, String deviceToken, String appVersion, String deviceModel,
                               String locale) {
    }

    @PostMapping
    @Operation(summary = "Register this device for the account signed in; registering it again only updates it")
    public ApiResponse<Void> register(@RequestBody RegisterBody body) {
        deviceService.register(body.platform(), body.deviceToken(), body.appVersion(), body.deviceModel(),
                body.locale());
        return ApiResponse.success();
    }

    @DeleteMapping("/{deviceToken}")
    @Operation(summary = "Stop push notifications to this device; only the person's own device is dropped")
    public ApiResponse<Void> unregister(@PathVariable String deviceToken) {
        deviceService.unregisterMine(deviceToken);
        return ApiResponse.success();
    }
}
