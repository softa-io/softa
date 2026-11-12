package io.softa.starter.message.mail.service;

import java.util.Optional;

import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.AbstractMailSendServer;

/**
 * What the tenant's and the platform's send server services share: the owner's default, keeping it
 * the only default, and the connectivity test. Each service reads and writes its own owner's table
 * only — {@link MailSendServerConfigService} a tenant's, {@link PlatformMailSendServerService} the
 * platform's.
 */
public interface MailSendServerService<T extends AbstractMailSendServer> extends MailServerService<T> {

    /** The owner's enabled default send server — the first by {@code sequence} if several are marked. */
    Optional<T> findDefault();

    /** Test connectivity using a transient server object (e.g. before saving). */
    ConnectivityTestResultDTO testConnectivity(T config);
}
