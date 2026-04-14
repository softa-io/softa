package io.softa.starter.message.mail.message;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.enums.SystemUser;
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
 * all {@link MailReceiveServerConfig} entries with {@code scheduledFetchEnabled = true}
 * and fetches new emails from each.
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
            subscriptionName = "${mq.topics.cron-task.mail-fetch-sub:mail-fetch-sub}")
    @SwitchUser(SystemUser.CRON_USER)
    public void onMessage(CronTaskMessage cronTaskMessage) {
        if (cronTaskMessage.getCronName() == null
                || !cronTaskMessage.getCronName().startsWith(CRON_NAME_PREFIX)) {
            return;
        }

        log.info("Scheduled mail fetch triggered by cron [id={}, name={}]",
                cronTaskMessage.getCronId(), cronTaskMessage.getCronName());

        List<MailReceiveServerConfig> configs = findScheduledConfigs();
        if (configs.isEmpty()) {
            log.debug("No receive server configs with scheduledFetchEnabled=true found.");
            return;
        }

        int totalFetched = 0;
        for (MailReceiveServerConfig config : configs) {
            try {
                int fetched = mailReceiveService.fetchNewMails(config.getId());
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
     * Find all enabled receive server configs that have scheduled fetch enabled.
     */
    private List<MailReceiveServerConfig> findScheduledConfigs() {
        Filters filters = new Filters()
                .eq(MailReceiveServerConfig::getScheduledFetchEnabled, true)
                .eq(MailReceiveServerConfig::getIsEnabled, true);
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(MailReceiveServerConfig::getSortOrder));
        return receiveConfigService.searchList(flexQuery);
    }
}
