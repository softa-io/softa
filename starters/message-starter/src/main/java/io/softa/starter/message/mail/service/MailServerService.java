package io.softa.starter.message.mail.service;

import io.softa.framework.orm.entity.AbstractModel;
import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;

/**
 * What every mail server service has — send or receive, a tenant's or the platform's — and the server
 * write endpoints rely on: one default per owner, and a connectivity test.
 */
public interface MailServerService<T extends AbstractModel> extends EntityService<T, Long> {

    /**
     * Demote every other default of the same owner so that {@code keptId} is the only one left with
     * {@code isDefault = true}. Called by the write endpoints after a row is saved as default; rows
     * authored by init scripts are untouched (the {@code sequence} ordering remains the tie-break).
     */
    void demoteOtherDefaults(Long keptId);

    /** Test connectivity and authentication for the server identified by {@code id}. */
    ConnectivityTestResultDTO testConnectivity(Long id);
}
