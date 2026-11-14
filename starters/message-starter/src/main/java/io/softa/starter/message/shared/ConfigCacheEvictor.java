package io.softa.starter.message.shared;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.message.mail.support.MailConfigCache;
import io.softa.starter.message.mail.support.PlatformMailConfigCache;
import io.softa.starter.message.sms.support.SmsConfigCache;

/**
 * Drops the cached send-server and SMS-provider configs a write changes, once the write commits — on every
 * write path, the generic endpoints the admin pages save through included. Those go straight to the model
 * service and never reach the services' own update and delete, so an edit used to take effect only when
 * its 5-minute cache entry expired. It is a write validator only for the write roots it is called at: it
 * never refuses anything, and runs after every real validator.
 * <p>
 * A config is cached by id and as its owner's default, so both go: the rows written, and the default of
 * the owner the write is about — a new or edited row may have become the default, or stopped being it.
 * Evicting after the commit, not before the write, keeps a send that reads the config in between from
 * caching the old row again. The Redis cache is the only thing to drop: {@code SmtpMailTransport} keys its
 * connection pools by the connection settings, so an edited server opens fresh connections on its next send.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ConfigCacheEvictor implements ModelWriteValidator {

    private static final String TENANT_ID = "tenantId";

    private final Map<String, Target> targets;

    public ConfigCacheEvictor(MailConfigCache tenantSendServers, PlatformMailConfigCache platformSendServers,
                              SmsConfigCache smsProviders) {
        this.targets = Map.of(
                "MailSendServerConfig", new Target(tenantSendServers, false),
                "PlatformMailSendServer", new Target(platformSendServers, true),
                "SmsProviderConfig", new Target(smsProviders, true));
    }

    /** A cached model: its cache, and whether its only owner is the platform. */
    private record Target(ConfigCache<?> cache, boolean platformOwned) {

        /** The owner whose default may have changed: the row's tenant, or the caller's when unknown. */
        long owner(Object rowTenant) {
            if (platformOwned) {
                return TenantScopes.PLATFORM;
            }
            return rowTenant instanceof Number tenant ? tenant.longValue() : TenantScopes.currentTenantOrPlatform();
        }
    }

    @Override
    public boolean supports(String modelName) {
        return targets.containsKey(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        Target target = targets.get(ctx.modelName());
        long owner = target.owner(ctx.row().get(TENANT_ID));
        afterCommit(() -> target.cache().evict(null, owner));
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        Target target = targets.get(ctx.modelName());
        Map<String, Object> stored = ctx.originalRow();
        long owner = target.owner(stored != null ? stored.get(TENANT_ID) : null);
        Long id = toLong(ctx.id());
        afterCommit(() -> target.cache().evict(id, owner));
    }

    @Override
    public void validateDelete(String modelName, List<? extends Serializable> ids) {
        Target target = targets.get(modelName);
        long owner = target.owner(null);
        List<Long> deleted = ids.stream().map(ConfigCacheEvictor::toLong).filter(Objects::nonNull).toList();
        afterCommit(() -> {
            target.cache().evict(null, owner);
            deleted.forEach(id -> target.cache().evict(id, owner));
        });
    }

    private static void afterCommit(Runnable eviction) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eviction.run();
                }
            });
        } else {
            eviction.run();
        }
    }

    private static Long toLong(Object id) {
        if (id instanceof Number number) {
            return number.longValue();
        }
        return id instanceof String text && !text.isBlank() ? Long.valueOf(text) : null;
    }
}
