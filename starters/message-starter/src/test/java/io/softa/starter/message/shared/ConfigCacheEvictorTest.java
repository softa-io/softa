package io.softa.starter.message.shared;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.message.mail.support.MailConfigCache;
import io.softa.starter.message.mail.support.PlatformMailConfigCache;
import io.softa.starter.message.sms.support.SmsConfigCache;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A save from any path — the admin pages' generic endpoints included — drops the cached config once it
 * commits, so the next send reads the edited row instead of the one cached up to 5 minutes earlier.
 */
class ConfigCacheEvictorTest {

    private final MailConfigCache tenantServers = mock(MailConfigCache.class);
    private final PlatformMailConfigCache platformServers = mock(PlatformMailConfigCache.class);
    private final SmsConfigCache smsProviders = mock(SmsConfigCache.class);
    private final ConfigCacheEvictor evictor = new ConfigCacheEvictor(tenantServers, platformServers, smsProviders);

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static void commit() {
        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        registered.forEach(TransactionSynchronization::afterCommit);
    }

    @Test
    void coversTheCachedConfigsOnly() {
        assertTrue(evictor.supports("MailSendServerConfig"));
        assertTrue(evictor.supports("PlatformMailSendServer"));
        assertTrue(evictor.supports("SmsProviderConfig"));
        assertTrue(!evictor.supports("MailReceiveServerConfig"));
    }

    @Test
    void anEditedTenantServerIsDroppedWithItsTenantsDefault_onceTheWriteCommits() {
        TransactionSynchronizationManager.initSynchronization();
        Map<String, Object> row = new HashMap<>(Map.of("id", 10L, "host", "smtp.new.example.com"));
        Map<String, Object> stored = Map.of("id", 10L, "tenantId", 42L, "host", "smtp.old.example.com");

        evictor.validateUpdate(WriteContext.of("MailSendServerConfig", AccessType.UPDATE, row, stored));
        verify(tenantServers, never()).evict(any(), anyLong());
        commit();

        verify(tenantServers).evict(10L, 42L);
    }

    @Test
    void aRolledBackWriteEvictsNothing() {
        TransactionSynchronizationManager.initSynchronization();
        Map<String, Object> row = new HashMap<>(Map.of("id", 20L));

        evictor.validateUpdate(WriteContext.of("PlatformMailSendServer", AccessType.UPDATE, row, Map.of("id", 20L)));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(platformServers, never()).evict(any(), anyLong());
    }

    @Test
    void aNewProviderDropsThePlatformsDefault_aDeletedOneItsOwnEntryToo() {
        evictor.validateCreate(WriteContext.of("SmsProviderConfig", AccessType.CREATE,
                new HashMap<>(Map.of("name", "Backup")), null));
        verify(smsProviders).evict(null, TenantScopes.PLATFORM);

        evictor.validateDelete("SmsProviderConfig", List.of(30L));
        verify(smsProviders).evict(30L, TenantScopes.PLATFORM);
    }

    @Test
    void aNewTenantServerDropsTheCallersDefault() {
        Context tenant = new Context();
        tenant.setTenantId(7L);

        ContextHolder.runWith(tenant, () -> evictor.validateCreate(WriteContext.of("MailSendServerConfig",
                AccessType.CREATE, new HashMap<>(Map.of("host", "smtp.example.com")), null)));

        verify(tenantServers).evict(null, 7L);
    }
}
