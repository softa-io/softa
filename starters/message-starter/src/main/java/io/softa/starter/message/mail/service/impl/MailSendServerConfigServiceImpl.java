package io.softa.starter.message.mail.service.impl;

import java.util.List;
import java.util.Optional;
import jakarta.mail.MessagingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.service.MailSendServerConfigService;
import io.softa.starter.message.mail.support.MailSenderFactory;

/**
 * Implementation of {@link MailSendServerConfigService}.
 */
@Slf4j
@Service
public class MailSendServerConfigServiceImpl extends EntityServiceImpl<MailSendServerConfig, Long>
        implements MailSendServerConfigService {

    @Autowired
    private MailSenderFactory senderFactory;

    @Override
    public Optional<MailSendServerConfig> findTenantDefault() {
        Filters filters = new Filters()
                .eq(MailSendServerConfig::getIsDefault, true)
                .eq(MailSendServerConfig::getIsEnabled, true);
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(MailSendServerConfig::getSortOrder));
        List<MailSendServerConfig> results = this.searchList(flexQuery);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    @CrossTenant
    public Optional<MailSendServerConfig> findPlatformDefault() {
        Filters filters = new Filters()
                .eq("tenantId", 0L)
                .eq(MailSendServerConfig::getIsDefault, true)
                .eq(MailSendServerConfig::getIsEnabled, true);
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(MailSendServerConfig::getSortOrder));
        List<MailSendServerConfig> results = this.searchList(flexQuery);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public boolean updateOne(MailSendServerConfig entity) {
        boolean result = super.updateOne(entity);
        if (result) {
            senderFactory.evict(entity.getId());
        }
        return result;
    }

    @Override
    public boolean deleteById(Long id) {
        boolean result = super.deleteById(id);
        if (result) {
            senderFactory.evict(id);
        }
        return result;
    }

    @Override
    public ConnectivityTestResultDTO testConnectivity(Long id) {
        MailSendServerConfig config = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Mail send server config with ID {0} not found.", id));
        return testConnectivity(config);
    }

    @Override
    public ConnectivityTestResultDTO testConnectivity(MailSendServerConfig config) {
        ConnectivityTestResultDTO result = new ConnectivityTestResultDTO();
        long start = System.currentTimeMillis();
        try {
            JavaMailSenderImpl sender = senderFactory.getSender(config);
            sender.testConnection();
            result.setSuccess(true);
            result.setServerGreeting("SMTP connection successful to " + config.getHost() + ":" + config.getPort());
        } catch (MessagingException e) {
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            log.warn("SMTP connectivity test failed for [{}:{}]: {}", config.getHost(), config.getPort(), e.getMessage());
        }
        result.setLatencyMs(System.currentTimeMillis() - start);
        return result;
    }
}
