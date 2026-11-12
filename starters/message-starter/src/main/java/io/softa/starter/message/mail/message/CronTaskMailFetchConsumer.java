package io.softa.starter.message.mail.message;

import java.util.List;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.SystemUser;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.annotation.SwitchUser;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.message.mail.entity.AbstractMailReceiveServer;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.entity.PlatformMailReceiveServer;
import io.softa.starter.message.mail.service.MailReceiveServerConfigService;
import io.softa.starter.message.mail.service.MailReceiveService;
import io.softa.starter.message.mail.service.PlatformMailReceiveServerService;
import io.softa.starter.message.shared.TenantScopes;

/**
 * Cron task consumer for scheduled mail fetching.
 * <p>
 * Listens to the cron-task Pulsar topic. When a cron event arrives whose
 * {@code cronName} starts with {@code "mail-fetch"}, this consumer polls every
 * enabled receive server — the platform's and every tenant's.
 * <p>
 * Mail belongs to the owner of the server it arrived through, so each fetch
 * runs in its owner's context: the platform's servers in the platform's, where
 * the mail is stored as {@code PlatformMailReceiveRecord}; a tenant's servers in
 * that tenant's, where the ORM stamps its {@code MailReceiveRecord}s and
 * watermarks with the tenant and the bounce correlation reads only that tenant's
 * send log. The tenant scan is {@code @CrossTenant}: one global cron polls every
 * tenant's inboxes.
 * <p>
 * The cron job is registered in {@code sys_cron} table with a name like
 * {@code "mail-fetch"} and a cron expression like {@code "0 * /5 * * * ?"}.
 */
@Slf4j
@Component
@ConditionalOnClass(name = "io.softa.starter.cron.message.dto.CronTaskMessage")
@ConditionalOnProperty(name = "mq.topics.cron-task.topic")
public class CronTaskMailFetchConsumer {

    /** Cron name prefix that this consumer handles. */
    private static final String CRON_NAME_PREFIX = "mail-fetch";

    @Autowired
    private MailReceiveService mailReceiveService;

    @Autowired
    private MailReceiveServerConfigService receiveConfigService;

    @Autowired
    private PlatformMailReceiveServerService platformReceiveService;

    @PulsarListener(
            topics = "${mq.topics.cron-task.topic}",
            subscriptionName = "${mq.topics.cron-task.mail-fetch-sub:mail-fetch-sub}",
            subscriptionType = SubscriptionType.Shared)
    @SwitchUser(SystemUser.CRON_USER)
    @CrossTenant
    public void onMessage(CronTaskMessage cronTaskMessage) {
        if (cronTaskMessage.getCronName() == null
                || !cronTaskMessage.getCronName().startsWith(CRON_NAME_PREFIX)) {
            return;
        }

        log.info("Scheduled mail fetch triggered by cron [id={}, name={}]",
                cronTaskMessage.getCronId(), cronTaskMessage.getCronName());

        List<PlatformMailReceiveServer> platformServers = platformReceiveService.searchList(enabledInPollingOrder());
        List<MailReceiveServerConfig> tenantServers = receiveConfigService.searchList(enabledInPollingOrder());
        if (platformServers.isEmpty() && tenantServers.isEmpty()) {
            log.debug("No enabled receive server configs found.");
            return;
        }

        int totalFetched = 0;
        for (PlatformMailReceiveServer server : platformServers) {
            totalFetched += fetch(server, TenantScopes.PLATFORM,
                    () -> mailReceiveService.fetchPlatformMails(server.getId()));
        }
        for (MailReceiveServerConfig server : tenantServers) {
            long tenant = server.getTenantId() != null ? server.getTenantId() : TenantScopes.PLATFORM;
            totalFetched += fetch(server, tenant, () -> mailReceiveService.fetchNewMails(server.getId()));
        }

        log.info("Scheduled mail fetch completed. Total fetched: {}", totalFetched);
    }

    /** Run one server's fetch in its owner's context; a failure is logged and the run goes on. */
    private int fetch(AbstractMailReceiveServer server, long ownerTenant, IntSupplier fetchOne) {
        Context ctx = ContextHolder.cloneContext();
        ctx.setTenantId(ownerTenant);
        ctx.setCrossTenant(false);
        ctx.setSkipPermissionCheck(true);
        try {
            int fetched = ContextHolder.callWith(ctx, fetchOne::getAsInt);
            log.info("Fetched {} email(s) from config [id={}, host={}]", fetched, server.getId(), server.getHost());
            return fetched;
        } catch (Exception e) {
            log.error("Failed to fetch from config [id={}, host={}]: {}",
                    server.getId(), server.getHost(), e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Every enabled server, ordered by {@code sequence} ascending (polling order, not failover
     * priority — all enabled servers are polled each tick).
     */
    private static FlexQuery enabledInPollingOrder() {
        return new FlexQuery(new Filters(), Orders.ofAsc(AbstractMailReceiveServer::getSequence));
    }
}
