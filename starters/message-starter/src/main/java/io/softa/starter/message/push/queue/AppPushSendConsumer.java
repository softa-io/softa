package io.softa.starter.message.push.queue;

import lombok.RequiredArgsConstructor;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code push-send} on a Shared subscription, so a large batch spreads over the consumers of every instance.
 * Keys: {@code mq.topics.push-send.topic}, {@code .sub}, {@code .concurrency} (consumers per instance, default 16).
 * Where {@code softa.message.dlq.topic} is set, a message that cannot even be read goes to the dead letters
 * ({@link WithDeadLetters}); processing itself never fails a message.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${mq.topics.push-send.topic:}' != '' and '${softa.message.dlq.topic:}' == ''")
public class AppPushSendConsumer {

    private final AppPushProcessor processor;

    @PulsarListener(topics = "${mq.topics.push-send.topic}",
                    subscriptionName = "${mq.topics.push-send.sub:push-send-sub}",
                    subscriptionType = SubscriptionType.Shared,
                    concurrency = "${mq.topics.push-send.concurrency:16}")
    public void onSend(AppPushMessage message) {
        processor.process(message);
    }

    /** The same consumer, its unreadable messages archived through message-starter's dead-letter pipeline. */
    @Component
    @RequiredArgsConstructor
    @ConditionalOnExpression("'${mq.topics.push-send.topic:}' != '' and '${softa.message.dlq.topic:}' != ''")
    public static class WithDeadLetters {

        private final AppPushProcessor processor;

        @PulsarListener(topics = "${mq.topics.push-send.topic}",
                        subscriptionName = "${mq.topics.push-send.sub:push-send-sub}",
                        subscriptionType = SubscriptionType.Shared,
                        concurrency = "${mq.topics.push-send.concurrency:16}",
                        deadLetterPolicy = "commonDlqPolicy")
        public void onSend(AppPushMessage message) {
            processor.process(message);
        }
    }
}
