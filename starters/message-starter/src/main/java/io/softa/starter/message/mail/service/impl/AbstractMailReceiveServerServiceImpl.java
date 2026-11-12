package io.softa.starter.message.mail.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.AbstractMailReceiveServer;
import io.softa.starter.message.mail.service.MailReceiveServerService;
import io.softa.starter.message.mail.support.MailSessionSettings;

/** The receive server logic both owners' tables share. A subclass binds its table. */
@Slf4j
public abstract class AbstractMailReceiveServerServiceImpl<T extends AbstractMailReceiveServer>
        extends EntityServiceImpl<T, Long> implements MailReceiveServerService<T> {

    @Autowired
    private MessageProperties messageProperties;

    @Override
    public Optional<T> findDefault() {
        // active = true is appended by the framework's active control.
        List<T> results = this.searchList(new FlexQuery(
                new Filters().eq(AbstractMailReceiveServer::getIsDefault, true),
                Orders.ofAsc(AbstractMailReceiveServer::getSequence)));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * The search sees exactly this owner's rows (a tenant's table is narrowed to the write path's tenant
     * by the ORM), so "other defaults" never reach across owners.
     */
    @Override
    public void demoteOtherDefaults(Long keptId) {
        FlexQuery flexQuery = new FlexQuery(new Filters().eq(AbstractMailReceiveServer::getIsDefault, true));
        flexQuery.setFilterControl(FilterControl.bypassActiveControl());
        for (T previous : this.searchList(flexQuery)) {
            if (Objects.equals(previous.getId(), keptId)) {
                continue;
            }
            Map<String, Object> demoted = new HashMap<>();
            demoted.put(ModelConstant.ID, previous.getId());
            demoted.put("isDefault", false);
            modelService.updateOne(modelName, demoted);
        }
    }

    @Override
    public ConnectivityTestResultDTO testConnectivity(Long id) {
        T config = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Mail receive server config with ID {0} not found.", id));
        return testConnectivity(config);
    }

    @Override
    public ConnectivityTestResultDTO testConnectivity(T config) {
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

    private Properties buildSessionProperties(T config, String protocol) {
        Properties props = new Properties();
        props.put("mail." + protocol + ".host", config.getHost());
        props.put("mail." + protocol + ".port", config.getPort());
        props.put("mail." + protocol + ".ssl.enable", Boolean.TRUE.equals(config.getSslEnabled()));
        props.put("mail." + protocol + ".auth", true);
        MailSessionSettings.apply(props, protocol, messageProperties.getMail(), config.getId());
        return props;
    }
}
