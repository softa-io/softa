package io.softa.starter.message;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.softa.starter.message.config.MessageProperties;

/**
 * Auto-configuration for {@code message-starter}.
 * <p>
 * Registered via {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * <p>
 * Wiring responsibilities:
 * <ul>
 *   <li>Component-scans the whole {@code io.softa.starter.message} tree
 *       (Mail / SMS / Inbox / delivery / maintenance).</li>
 *   <li>Binds the starter's {@code @ConfigurationProperties}
 *       ({@code softa.message.*}).</li>
 *   <li>Enables Spring's scheduler for the {@code DeliveryPoller} and the
 *       {@code ZombieRecordSweeper}.</li>
 * </ul>
 * Individual components are gated by {@code @ConditionalOnProperty} where
 * appropriate (e.g. the Pulsar consumers only activate when their topic is
 * configured). The delivery loop and the stale-send sweep always run: an
 * application with message-starter sends.
 */
@ComponentScan
@EnableScheduling
@EnableConfigurationProperties(MessageProperties.class)
public class MessageAutoConfiguration {
}
