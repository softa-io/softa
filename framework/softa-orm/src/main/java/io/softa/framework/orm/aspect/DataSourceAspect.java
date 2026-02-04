package io.softa.framework.orm.aspect;

import java.lang.reflect.Method;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.orm.annotation.DataSource;
import io.softa.framework.orm.datasource.DataSourceHolder;


/**
 * Datasource annotation aspect.
 * Switch the datasource according to the annotation.
 *  1. If the method does not have the DataSource annotation, get the class level annotation.
 *  2. If the previous data source is the same as the current data source, no need to switch.
 *  3. If the previous data source is different from the current data source, throw an exception.
 *  4. If the previous data source is null, set the current data source.
 *  5. If the data source needs to be switched, clear the data source after the method is executed.
 */
@Aspect
@Component
@ConditionalOnProperty(name = "spring.datasource.dynamic.enable", havingValue = "true")
public class DataSourceAspect {

    @Around("@annotation(io.softa.framework.orm.annotation.DataSource)" +
            " || @within(io.softa.framework.orm.annotation.DataSource)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        DataSource dataSource = method.getAnnotation(DataSource.class);
        if (dataSource == null) {
            // If the method does not have the DataSource annotation, get the class level annotation.
            dataSource = joinPoint.getTarget().getClass().getAnnotation(DataSource.class);
        }
        if (dataSource == null) {
            // No DataSource annotation found, proceed the method directly.
            return joinPoint.proceed();
        }
        String currentDataSource = dataSource.value();
        // Check if the data source needs to be switched.
        String previousDataSource = DataSourceHolder.getDataSourceKey();
        if (StringUtils.isBlank(previousDataSource)) {
            return DataSourceHolder.callWithDataSource(currentDataSource, joinPoint::proceed);
        } else if (previousDataSource.equals(currentDataSource)) {
            // If the previous data source is the same as the current data source, no need to switch.
            return joinPoint.proceed();
        } else {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                // If in transaction, throw an exception.
                throw new RuntimeException("""
                        Cannot switch to different datasource in transactional operation.
                        Current datasource: %s, trying to switch to: %s.
                        """.formatted(previousDataSource, currentDataSource));
            }
            return DataSourceHolder.callWithDataSource(currentDataSource, joinPoint::proceed);
        }
    }

}
