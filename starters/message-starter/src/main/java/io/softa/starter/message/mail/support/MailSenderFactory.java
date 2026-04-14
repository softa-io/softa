package io.softa.starter.message.mail.support;

import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.enums.AuthType;
import io.softa.framework.orm.service.CacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and caches {@link JavaMailSenderImpl} instances keyed by {@link MailSendServerConfig} ID.
 * <p>
 * Uses a local {@link ConcurrentHashMap} for the non-serializable {@code JavaMailSenderImpl} objects,
 * and Redis (via {@link CacheService}) to store config snapshots so that configuration changes
 * are detected across multiple application instances.
 * <p>
 * Call {@link #evict(Long)} after updating a config to invalidate both Redis and local cache.
 */
@Slf4j
@Component
public class MailSenderFactory {

    private static final String CACHE_KEY_PREFIX = "mail-sender-config:";
    private static final int CACHE_EXPIRE_SECONDS = 300;

    private final ConcurrentHashMap<Long, JavaMailSenderImpl> localCache = new ConcurrentHashMap<>();

    @Autowired
    private CacheService cacheService;

    @Autowired
    private Environment environment;

    /**
     * Return a cached sender if the config has not changed, or build a new one.
     * <p>
     * The current config is serialized and compared with the Redis-stored snapshot.
     * If they differ (config was updated on another instance), the local sender is rebuilt.
     */
    public JavaMailSenderImpl getSender(MailSendServerConfig config) {
        Long configId = config.getId();
        String currentFingerprint = String.valueOf(config.getUpdatedTime());
        String cachedFingerprint = cacheService.get(CACHE_KEY_PREFIX + configId);

        if (cachedFingerprint != null && Objects.equals(currentFingerprint, cachedFingerprint)
                && localCache.containsKey(configId)) {
            return localCache.get(configId);
        }

        JavaMailSenderImpl sender = buildSender(config);
        localCache.put(configId, sender);
        cacheService.save(CACHE_KEY_PREFIX + configId, currentFingerprint, CACHE_EXPIRE_SECONDS);
        return sender;
    }

    /**
     * Invalidate the cached sender for {@code serverConfigId}.
     * Clears both the Redis snapshot and the local sender instance.
     */
    public void evict(Long serverConfigId) {
        localCache.remove(serverConfigId);
        cacheService.clear(CACHE_KEY_PREFIX + serverConfigId);
    }

    private JavaMailSenderImpl buildSender(MailSendServerConfig config) {
        if (AuthType.OAUTH2.equals(config.getAuthType())) {
            throw new UnsupportedOperationException(
                    "OAuth2 mail sending is not yet implemented. Use PASSWORD auth type.");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.getHost());
        sender.setPort(config.getPort());
        sender.setUsername(config.getUsername());
        sender.setPassword(config.getPassword());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", config.getProtocol().getCode().toLowerCase());
        props.put("mail.smtp.auth", true);
        props.put("mail.smtp.starttls.enable", Boolean.TRUE.equals(config.getStarttlsEnabled()));
        props.put("mail.smtp.ssl.enable", Boolean.TRUE.equals(config.getSslEnabled()));

        int connTimeout = config.getConnectionTimeoutMs() != null ? config.getConnectionTimeoutMs() : 5000;
        int readTimeout = config.getReadTimeoutMs() != null ? config.getReadTimeoutMs() : 30000;
        props.put("mail.smtp.connectiontimeout", connTimeout);
        props.put("mail.smtp.timeout", readTimeout);

        if (Boolean.TRUE.equals(config.getDebugEnabled())) {
            if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
                throw new IllegalStateException(
                        "SMTP debug mode (debugEnabled=true) is not allowed in production. "
                        + "It exposes AUTH credentials in stdout. Disable it for config id="
                        + config.getId());
            }
            log.warn("SMTP debug logging is ENABLED for config id={}. "
                    + "This will expose AUTH credentials in stdout.", config.getId());
            props.put("mail.debug", "true");
        }

        return sender;
    }
}
