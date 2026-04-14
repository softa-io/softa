package io.softa.starter.message.mail.service.impl;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.service.MailReceiveServerConfigService;

/**
 * Implementation of {@link MailReceiveServerConfigService}.
 */
@Slf4j
@Service
public class MailReceiveServerConfigServiceImpl extends EntityServiceImpl<MailReceiveServerConfig, Long>
        implements MailReceiveServerConfigService {

    @Override
    public Optional<MailReceiveServerConfig> findTenantDefault() {
        Filters filters = new Filters()
                .eq(MailReceiveServerConfig::getIsDefault, true)
                .eq(MailReceiveServerConfig::getIsEnabled, true);
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(MailReceiveServerConfig::getSortOrder));
        List<MailReceiveServerConfig> results = this.searchList(flexQuery);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    @CrossTenant
    public Optional<MailReceiveServerConfig> findPlatformDefault() {
        Filters filters = new Filters()
                .eq("tenantId", 0L)
                .eq(MailReceiveServerConfig::getIsDefault, true)
                .eq(MailReceiveServerConfig::getIsEnabled, true);
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(MailReceiveServerConfig::getSortOrder));
        List<MailReceiveServerConfig> results = this.searchList(flexQuery);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public ConnectivityTestResultDTO testConnectivity(Long id) {
        MailReceiveServerConfig config = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Mail receive server config with ID {0} not found.", id));
        return testConnectivity(config);
    }

    @Override
    public ConnectivityTestResultDTO testConnectivity(MailReceiveServerConfig config) {
        ConnectivityTestResultDTO result = new ConnectivityTestResultDTO();
        long start = System.currentTimeMillis();
        String protocol = config.getProtocol().getCode().toLowerCase();
        try {
            Properties props = buildSessionProperties(config, protocol);
            Session session = Session.getInstance(props);
            try (Store store = session.getStore(protocol)) {
                store.connect(config.getHost(), config.getPort(), config.getUsername(), config.getPassword());
                result.setSuccess(true);
                result.setServerGreeting(protocol.toUpperCase() + " connection successful to "
                        + config.getHost() + ":" + config.getPort());
            }
        } catch (MessagingException e) {
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            log.warn("IMAP/POP3 connectivity test failed for [{}:{}]: {}", config.getHost(), config.getPort(), e.getMessage());
        }
        result.setLatencyMs(System.currentTimeMillis() - start);
        return result;
    }

    private Properties buildSessionProperties(MailReceiveServerConfig config, String protocol) {
        Properties props = new Properties();
        props.put("mail." + protocol + ".host", config.getHost());
        props.put("mail." + protocol + ".port", config.getPort());
        props.put("mail." + protocol + ".ssl.enable", Boolean.TRUE.equals(config.getSslEnabled()));
        props.put("mail." + protocol + ".auth", true);
        int connTimeout = config.getConnectionTimeoutMs() != null ? config.getConnectionTimeoutMs() : 5000;
        int readTimeout = config.getReadTimeoutMs() != null ? config.getReadTimeoutMs() : 30000;
        props.put("mail." + protocol + ".connectiontimeout", connTimeout);
        props.put("mail." + protocol + ".timeout", readTimeout);
        return props;
    }
}
