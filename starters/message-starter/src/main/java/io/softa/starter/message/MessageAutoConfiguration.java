package io.softa.starter.message;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mq.MqTopicsProperties;
import io.softa.starter.message.shared.retry.RetryProperties;

/**
 * Auto-configuration for {@code message-starter}.
 * <p>
 * Registered via {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * <p>
 * Wiring responsibilities:
 * <ul>
 *   <li>Component-scans the whole {@code io.softa.starter.message} tree
 *       (Mail / SMS / Inbox / MQ / maintenance).</li>
 *   <li>Binds all {@code @ConfigurationProperties} for the starter
 *       (topics, message, retry) in one place.</li>
 *   <li>Enables Spring's scheduler for {@code OutboxPublisher} and the
 *       {@code ZombieRecordSweeper}.</li>
 * </ul>
 * Individual components are gated by {@code @ConditionalOnProperty} where
 * appropriate (e.g. consumers only activate when their topic is configured;
 * the outbox publisher can be disabled via {@code softa.message.outbox.enabled=false}).
 */
@ComponentScan
@EnableScheduling
@EnableConfigurationProperties({MqTopicsProperties.class, MessageProperties.class,
        RetryProperties.class})
public class MessageAutoConfiguration {
}
