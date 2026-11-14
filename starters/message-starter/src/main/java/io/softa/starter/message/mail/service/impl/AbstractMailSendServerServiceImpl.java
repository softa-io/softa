package io.softa.starter.message.mail.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import jakarta.mail.MessagingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.AbstractMailSendServer;
import io.softa.starter.message.mail.service.MailSendServerService;
import io.softa.starter.message.mail.smtp.SmtpMailTransport;

/**
 * The send server logic both owners' tables share; a subclass binds its table. The Redis config cache is
 * dropped after every committed write by {@link io.softa.starter.message.shared.ConfigCacheEvictor},
 * whatever path the write comes through, so no write here needs to evict it.
 */
@Slf4j
public abstract class AbstractMailSendServerServiceImpl<T extends AbstractMailSendServer>
        extends EntityServiceImpl<T, Long> implements MailSendServerService<T> {

    @Autowired
    private SmtpMailTransport smtpMailTransport;

    @Override
    public Optional<T> findDefault() {
        // active = true is appended by the framework's active control.
        List<T> results = this.searchList(new FlexQuery(
                new Filters().eq(AbstractMailSendServer::getIsDefault, true),
                Orders.ofAsc(AbstractMailSendServer::getSequence)));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * The search sees exactly this owner's rows (a tenant's table is narrowed to the write path's tenant
     * by the ORM), so "other defaults" never reach across owners.
     */
    @Override
    public void demoteOtherDefaults(Long keptId) {
        // Includes disabled rows: a demotion that skipped them would leave a
        // second isDefault=true row waiting to re-appear when it is re-enabled.
        FlexQuery flexQuery = new FlexQuery(new Filters().eq(AbstractMailSendServer::getIsDefault, true));
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
                        "Mail send server config with ID {0} not found.", id));
        return testConnectivity(config);
    }

    @Override
    public ConnectivityTestResultDTO testConnectivity(T config) {
        ConnectivityTestResultDTO result = new ConnectivityTestResultDTO();
        long start = System.currentTimeMillis();
        try {
            JavaMailSenderImpl sender = smtpMailTransport.buildSender(config);
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
