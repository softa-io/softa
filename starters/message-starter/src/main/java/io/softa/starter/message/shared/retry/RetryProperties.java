package io.softa.starter.message.shared.retry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for {@link ExponentialBackoffPolicy}. Exposed under
 * {@code softa.message.retry.*} so ops can change the back-off shape.
 * <p>
 * Registered via {@code @EnableConfigurationProperties} on
 * {@code MessageAutoConfiguration}.
 */
@Data
@ConfigurationProperties(prefix = "softa.message.retry")
public class RetryProperties {

    /** Global cap on retry attempts before a record is dead-lettered. */
    private int defaultMaxAttempts = 5;

    private final Exponential exponential = new Exponential();

    @Data
    public static class Exponential {
        /** Base delay seconds; the first retry waits this long. */
        private int baseSeconds = 30;
        /** Upper bound. Prevents back-off runaway on long-running outages. */
        private int maxSeconds = 3_600;
        /** Geometric multiplier applied per attempt. 2.0 = double each time. */
        private double multiplier = 2.0;
        /** Random jitter factor in {@code [0, jitter]}; 0.5 = ±50% band. */
        private double jitter = 0.5;
        /** Floor applied to QUOTA errors — providers won't love us retrying in 30s after a 429. */
        private int quotaFloorSeconds = 300;
    }
}
