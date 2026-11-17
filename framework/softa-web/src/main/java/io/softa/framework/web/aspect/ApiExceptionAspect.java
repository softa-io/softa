package io.softa.framework.web.aspect;

import java.io.InputStream;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.web.utils.SecretMasker;

/**
 * Collect API info when an exception occurs, including the URL, the parameters, and the Request Body.
 * Since the HTTP request body can only be read once, to capture the request body in case of an exception,
 * the parameters are collected via an aspect at the point of exception and stored in request attributes.
 * Credentials are masked before they are stored ({@link SecretMasker}): a parameter with a secret name, and
 * the secret members of a parameter written out as JSON.
 * Aspect applies to: Classes annotated with @RestController or methods annotated with @PostMapping/@GetMapping.
 */
@Slf4j
@Aspect
@Component
public class ApiExceptionAspect {

    public static final String REQUEST_PARAMS = "RequestParams";

    @Autowired
    private HttpServletRequest request;

    /**
     * After throwing an exception, extract the parameters via an aspect and fill them into
     * the request attributes for use in exception handling.
     */
    @AfterThrowing(pointcut = "@within(org.springframework.web.bind.annotation.RestController) || @annotation(org.springframework.web.bind.annotation.PostMapping) || @annotation(org.springframework.web.bind.annotation.GetMapping)", throwing = "e")
    public void afterThrowing(JoinPoint joinPoint, Exception e) {
        Signature signature = joinPoint.getSignature();
        if (signature instanceof MethodSignature methodSignature) {
            Object[] args = joinPoint.getArgs();
            String[] parameterNames = methodSignature.getParameterNames();
            if (parameterNames == null || parameterNames.length == 0) {
                return;
            }
            StringBuilder stringBuilder = new StringBuilder("API Method Parameters: ");
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                String parameterName = parameterNames[i];
                String argStr;
                if (arg != null && SecretMasker.isSecretName(parameterName)) {
                    argStr = SecretMasker.MASK;
                } else if (arg instanceof InputStream || arg instanceof InputStreamSource) {
                    argStr =  arg.toString();
                } else {
                    try {
                        argStr = SecretMasker.maskJson(JsonUtils.objectToString(arg));
                    } catch (Exception ex) {
                        argStr = arg.toString();
                    }
                }
                stringBuilder.append(parameterName).append(" = ").append(argStr).append(", ");
            }
            request.setAttribute(REQUEST_PARAMS, stringBuilder.toString());
        }
    }

}