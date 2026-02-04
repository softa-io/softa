package io.softa.framework.orm.aspect;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.rpc.RemoteApiClient;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Switch rpc service according to the model attribute.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "rpc.enable", havingValue = "true")
public class SwitchServiceAspect {

    @Autowired
    private RemoteApiClient apiClient;

    /**
     * The first parameter of the annotated method must be the model name.
     */
    @Around("@within(io.softa.framework.orm.annotation.RpcCheckpoint) || " +
            "@annotation(io.softa.framework.orm.annotation.RpcCheckpoint)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] methodArgs = joinPoint.getArgs();
        if (!(methodArgs[0] instanceof String modelName)) {
            return joinPoint.proceed();
        }
        // The system model cannot switch service.
        if (ModelConstant.SYSTEM_MODEL.contains(modelName)) {
            return joinPoint.proceed();
        }
        String remoteServiceName = ModelManager.getModel(modelName).getServiceName();
        if (StringUtils.isBlank(remoteServiceName)) {
            return joinPoint.proceed();
        }
        // Call the remote service.
        String methodName = joinPoint.getSignature().getName();
        return apiClient.modelRpc(remoteServiceName, modelName, methodName, methodArgs);
    }

}
