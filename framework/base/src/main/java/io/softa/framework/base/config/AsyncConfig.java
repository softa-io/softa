package io.softa.framework.base.config;

import io.softa.framework.base.context.ContextHolder;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Async configuration using virtual thread for global async task executor
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final TaskDecorator cloneContextDecorator = ContextHolder::wrap;

    /**
     * Get async task executor using virtual thread
     *
     * @return async task executor
     */
    @Override
    public Executor getAsyncExecutor() {
        return task -> executor.submit(cloneContextDecorator.decorate(task)
        );
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("Async error in {} with params {}", method, params, ex);
    }

    @PreDestroy
    public void destroy() {
        executor.shutdown();
    }
}
