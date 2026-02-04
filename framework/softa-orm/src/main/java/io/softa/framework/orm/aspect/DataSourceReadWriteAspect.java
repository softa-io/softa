package io.softa.framework.orm.aspect;

import io.softa.framework.orm.annotation.WriteOperation;
import io.softa.framework.orm.datasource.DataSourceConfig;
import io.softa.framework.orm.datasource.DataSourceHolder;
import io.softa.framework.orm.datasource.ReadonlyDataSourceHolder;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;


/**
 * Read-write-separation datasource aspect.
 * DataSource routing according to the read-write-separation strategy.
 *  1. If in transaction, use the primary datasource.
 *  2. If not in transaction, and execute write operation, use the primary datasource.
 *  3. If not in transaction and not execute write operation, and already set datasource, use the specified datasource.
 *  4. If not in transaction and not execute write operation, and not set datasource, use the random readonly datasource.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "spring.datasource.dynamic.enable", havingValue = "true")
@ConditionalOnExpression("'${spring.datasource.dynamic.mode:}' == 'read-write-separation'")
public class DataSourceReadWriteAspect {

    @Around("@annotation(io.softa.framework.orm.annotation.ExecuteSql)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        String previousDataSource = DataSourceHolder.getDataSourceKey();
        boolean isTransactionActive = TransactionSynchronizationManager.isActualTransactionActive();
        String primaryDataSourceKey = DataSourceConfig.getPrimaryDataSourceKey();
        // 1. If in transaction, always use the primary datasource.
        if (isTransactionActive) {
            return DataSourceHolder.callWithDataSource(primaryDataSourceKey, joinPoint::proceed);
        }

        // 2. Non-transactional write: use primary datasource in a new scope.
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        WriteOperation writeOperation = method.getAnnotation(WriteOperation.class);
        if (writeOperation != null) {
            return DataSourceHolder.callWithDataSource(primaryDataSourceKey, joinPoint::proceed);
        }

        // 3. Non-transactional read: use readonly datasource if not set.
        if (StringUtils.isBlank(previousDataSource)) {
            // Not in transaction, not execute write operation, and not set datasource, use the readonly datasource.
            String readonlyDataSourceKey = ReadonlyDataSourceHolder.getReadonlyDataSourceKey();
            if (StringUtils.isNotBlank(readonlyDataSourceKey)) {
                return DataSourceHolder.callWithDataSource(readonlyDataSourceKey, joinPoint::proceed);
            } else {
                // no readonly datasource configured, proceed without switching
                return joinPoint.proceed();
            }
        }

        // 4. Already set datasource, proceed directly.
        return joinPoint.proceed();
    }

}
