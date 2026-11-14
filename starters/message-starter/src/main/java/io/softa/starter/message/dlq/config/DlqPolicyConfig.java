package io.softa.starter.message.dlq.config;

import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Unified Pulsar DLQ policy for message-starter consumers.
 *
 * <p>Exposes a single shared DeadLetterPolicy bean named {@code commonDlqPolicy}. 
 * Any {@code @PulsarListener} can opt into the unified DLQ pipeline by referencing it by name:
 * <pre>
 *   PulsarListener(deadLetterPolicy = "commonDlqPolicy", ...)
 * </pre>
 * All failed messages funnel into the configured DLQ topic,
 * {@code DeadLetterConsumer} archives them to {@code dead_letter_message}.
 *
 * <p><strong>Activation</strong>: bean is only created when {@code softa.message.dlq.topic} is configured
 * applications that do not need a DLQ pipeline simply omit the property and pay nothing.
 *
 * <p><strong>Configuration keys</strong>:
 * <ul>
 *   <li>{@code softa.message.dlq.topic} — required to enable. 
 *      Pulsar topic the broker forwards exhausted messages to.</li>
 *   <li>{@code softa.message.dlq.max-redeliver} — optional, default {@code 2}.
 *       Max nack count before the broker dead-letters.</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "softa.message.dlq", name = "topic")
public class DlqPolicyConfig {
    
    @Bean("commonDlqPolicy")
    public DeadLetterPolicy commonDlqPolicy(
            @Value("${softa.message.dlq.topic}") String dlqTopic, 
            @Value("${softa.message.dlq.max-redeliver:2}") int maxRedeliver) {
        return DeadLetterPolicy.builder()
                .maxRedeliverCount(maxRedeliver)
                .deadLetterTopic(dlqTopic)
                .build();
    }
}
