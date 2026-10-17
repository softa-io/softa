package io.softa.framework.web.controller;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.ConfigurationException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.base.utils.SerializeUtils;
import io.softa.framework.base.utils.SpringContextUtils;
import io.softa.framework.orm.config.RPCProperties;
import io.softa.framework.orm.constant.RPCConstant;
import io.softa.framework.orm.jdbc.JdbcServiceImpl;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.rpc.RPCRequestBody;

/**
 * Server side of the internal model RPC ({@link io.softa.framework.web.rpc.RPCClientImpl}).
 * <p>
 * This endpoint runs below {@code ModelService} with a caller-supplied {@link Context}, so it is
 * a service-to-service channel only: the caller must present this application's
 * {@code rpc.access-key} / {@code rpc.secret-key}, and a request bound to a logged-in user is
 * refused whatever headers it carries. Both checks run before the body is deserialized, so an
 * unauthenticated payload is never read.
 */
@Slf4j
@RestController
@RequestMapping(RPCConstant.RPC_ROUTE)
@Hidden
public class RPCController {

    @Autowired
    private RPCProperties rpcProperties;

    @PostMapping(value = "/{modelName}/{methodName}", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ApiResponse<?> handleRPCRequest(@PathVariable("modelName") String modelName,
                                           @PathVariable("methodName") String methodName,
                                           @RequestHeader(value = RPCConstant.RPC_HEADER_API_KEY, required = false) String apiKey,
                                           @RequestHeader(value = RPCConstant.RPC_HEADER_API_SECRET, required = false) String apiSecret,
                                           @RequestBody String serializedBase64) {
        if (!rpcProperties.isEnable()) {
            throw new ConfigurationException("RPC service is not enabled for application {0}.", SystemConfig.env.getName());
        }
        authenticateCaller(apiKey, apiSecret);
        RPCRequestBody rpcRequestBody = SerializeUtils.deserialize(serializedBase64, RPCRequestBody.class);
        Object[] methodArgs = rpcRequestBody.getMethodArgs();
        Class<?>[] argsClass = Arrays.stream(methodArgs).map(Object::getClass).toArray(Class<?>[]::new);
        // Find the method by methodName and args types.
        Method method = ReflectionUtils.findMethod(JdbcServiceImpl.class, methodName, argsClass);
        if (method == null) {
            throw new IllegalArgumentException("RPC method for `/{0}/{1}` not found, requestBody: {2}",
                    modelName, methodName, rpcRequestBody);
        }
        Context ctx = rpcRequestBody.getContext();
        return ContextHolder.callWith(ctx, () -> {
            Object targetBean = SpringContextUtils.getBeanByClass(JdbcServiceImpl.class);
            Object result = ReflectionUtils.invokeMethod(method, targetBean, methodArgs);
            Object rpcResultData = SerializeUtils.serialize(result);
            return ApiResponse.success(rpcResultData);
        });
    }

    /**
     * Admit only a peer service holding this application's RPC credentials. An application that
     * enables RPC without configuring both credentials accepts no caller at all.
     */
    private void authenticateCaller(String apiKey, String apiSecret) {
        if (ContextHolder.existContext() && ContextHolder.getContext().getUserId() != null) {
            throw new PermissionException("RPC endpoint accepts service calls only.");
        }
        String expectedKey = rpcProperties.getAccessKey();
        String expectedSecret = rpcProperties.getSecretKey();
        if (StringUtils.isAnyBlank(expectedKey, expectedSecret)) {
            log.warn("RPC request refused: rpc.access-key / rpc.secret-key are not configured.");
            throw new PermissionException("RPC endpoint is not accepting calls.");
        }
        // Compare both values in full regardless of the first result, in constant time.
        boolean keyMatches = constantTimeEquals(expectedKey, apiKey);
        boolean secretMatches = constantTimeEquals(expectedSecret, apiSecret);
        if (!keyMatches || !secretMatches) {
            throw new PermissionException("RPC credentials are invalid.");
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

}
