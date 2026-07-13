package io.softa.starter.message.mail.message;

import java.util.List;
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
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.service.MailReceiveServerConfigService;
import io.softa.starter.message.mail.service.MailReceiveService;

/**
 * Cron task consumer for scheduled mail fetching.
 * <p>
 * Listens to the cron-task Pulsar topic. When a cron event arrives whose
 * {@code cronName} starts with {@code "mail-fetch"}, this consumer queries
 * all enabled {@link MailReceiveServerConfig} entries and fetches new emails
 * from each.
 * <p>
 * Tenancy: the config scan is {@code @CrossTenant} (one global cron polls every
 * tenant's inboxes), but each config's fetch runs inside <b>that config's
 * tenant context</b> so the persisted {@code MailReceiveRecord}s / watermark
 * rows are stamped with — and the bounce correlation reads — the owning tenant.
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

        List<MailReceiveServerConfig> configs = findEnabledConfigs();
        if (configs.isEmpty()) {
            log.debug("No enabled receive server configs found.");
            return;
        }

        int totalFetched = 0;
        for (MailReceiveServerConfig config : configs) {
            try {
                int fetched = fetchInTenantContext(config);
                totalFetched += fetched;
                log.info("Fetched {} email(s) from config [id={}, host={}]",
                        fetched, config.getId(), config.getHost());
            } catch (Exception e) {
                log.error("Failed to fetch from config [id={}, host={}]: {}",
                        config.getId(), config.getHost(), e.getMessage(), e);
            }
        }

        log.info("Scheduled mail fetch completed. Total fetched: {}", totalFetched);
    }

    /**
     * Run one config's fetch inside the owning tenant's context so persisted
     * records and watermark rows carry the right tenant stamp, and the bounce
     * correlation only sees that tenant's send log.
     */
    private int fetchInTenantContext(MailReceiveServerConfig config) {
        Context ctx = ContextHolder.cloneContext();
        ctx.setTenantId(config.getTenantId() != null ? config.getTenantId() : 0L);
        ctx.setCrossTenant(false);
        ctx.setSkipPermissionCheck(true);
        int[] fetched = new int[1];
        ContextHolder.runWith(ctx, () -> fetched[0] = mailReceiveService.fetchNewMails(config.getId()));
        return fetched[0];
    }

    /**
     * Find all enabled receive server configs, ordered by {@code sequence} ascending
     * (polling order, not failover priority — all enabled configs are polled each tick).
     */
    private List<MailReceiveServerConfig> findEnabledConfigs() {
        Filters filters = new Filters()
                .eq(MailReceiveServerConfig::getIsEnabled, true);
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(MailReceiveServerConfig::getSequence));
        return receiveConfigService.searchList(flexQuery);
    }
}
