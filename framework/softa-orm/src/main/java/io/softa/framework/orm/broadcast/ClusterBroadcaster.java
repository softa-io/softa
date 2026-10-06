package io.softa.framework.orm.broadcast;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import io.softa.framework.orm.service.CacheService;

/**
 * Tells every running instance of the application that something happened, over Redis pub/sub.
 *
 * <p>For state each instance keeps in its own memory: a Redis key is shared and cleared once, but an
 * in-memory index is rebuilt only by the instance holding it, and a request reaches only one instance.
 * Every instance subscribes at startup; a published event reaches all of them, the publisher included.
 *
 * <p>The channel is under the root key, so applications sharing one Redis server do not hear each
 * other's events. Delivery is at most once: an instance disconnected from Redis at that moment misses
 * the event, and catches up only when it next rebuilds for another reason, such as a restart.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class ClusterBroadcaster {

    private static final String CHANNEL = "cluster-broadcast";

    private final StringRedisTemplate redisTemplate;
    private final String channel;

    public ClusterBroadcaster(StringRedisTemplate redisTemplate, CacheService cacheService) {
        this.redisTemplate = redisTemplate;
        this.channel = cacheService.getKeyPath(CHANNEL);
    }

    /**
     * Publish an event to every instance, this one included.
     *
     * @param event event name, matched against {@link ClusterBroadcastListener#event()}
     */
    public void publish(String event) {
        Long receivers = redisTemplate.convertAndSend(channel, event);
        log.info("Broadcast {} on {} reached {} instance(s)", event, channel, receivers);
    }

    @Bean
    RedisMessageListenerContainer clusterBroadcastContainer(RedisConnectionFactory connectionFactory,
                                                            ObjectProvider<ClusterBroadcastListener> listeners) {
        Map<String, List<ClusterBroadcastListener>> byEvent = listeners.orderedStream()
                .collect(Collectors.groupingBy(ClusterBroadcastListener::event));
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String event = new String(message.getBody(), StandardCharsets.UTF_8);
            dispatch(byEvent.getOrDefault(event, List.of()), event);
        }, new ChannelTopic(channel));
        return container;
    }

    /** One failing listener must not keep the others from running. Package-private for the test. */
    static void dispatch(List<ClusterBroadcastListener> listeners, String event) {
        for (ClusterBroadcastListener listener : listeners) {
            try {
                listener.onEvent();
            } catch (RuntimeException e) {
                log.error("Listener {} failed on broadcast {}", listener.getClass().getName(), event, e);
            }
        }
    }
}
