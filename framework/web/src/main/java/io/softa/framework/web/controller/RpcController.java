package io.softa.framework.web.controller;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.ConfigurationException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.SerializeUtils;
import io.softa.framework.base.utils.SpringContextUtils;
import io.softa.framework.orm.config.RPCProperties;
import io.softa.framework.orm.jdbc.JdbcServiceImpl;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.rpc.RpcRequestBody;
import io.swagger.v3.oas.annotations.Hidden;
import java.lang.reflect.Method;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/rpc")
@Hidden
public class RpcController {

    @Autowired
    private RPCProperties rpcProperties;

    @PostMapping(value = "/{modelName}/{methodName}", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ApiResponse<?> handleRpcRequest(@PathVariable("modelName") String modelName,
                                           @PathVariable("methodName") String methodName,
                                           @RequestBody String serializedBase64) {
        if (!rpcProperties.isEnable()) {
            throw new ConfigurationException("RPC service is not enabled for application {0}.", SystemConfig.env.getName());
        }
        RpcRequestBody rpcRequestBody = SerializeUtils.deserialize(serializedBase64, RpcRequestBody.class);
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

}