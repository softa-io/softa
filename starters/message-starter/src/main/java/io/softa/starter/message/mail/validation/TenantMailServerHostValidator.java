package io.softa.starter.message.mail.validation;

import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.message.mail.support.TenantMailHostGuard;

/**
 * Refuses a tenant's own mail server — send or receive — whose host is, or resolves to, an address
 * inside the deployment, on every write path. A host that does not resolve yet is let through here;
 * {@link TenantMailHostGuard} checks again before every connection. The platform's servers are not
 * checked.
 */
@Component
@Order(150)
public class TenantMailServerHostValidator implements ModelWriteValidator {

    static final String HOST = "host";

    private static final Set<String> MODELS = Set.of("MailSendServerConfig", "MailReceiveServerConfig");

    private final TenantMailHostGuard hostGuard;

    public TenantMailServerHostValidator(TenantMailHostGuard hostGuard) {
        this.hostGuard = hostGuard;
    }

    @Override
    public boolean supports(String modelName) {
        return MODELS.contains(modelName) && hostGuard.active();
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        check(ctx);
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        if (ctx.patch().containsKey(HOST)) {
            check(ctx);
        }
    }

    private void check(WriteContext ctx) {
        Object host = ctx.row().get(HOST);
        String refusal = host == null ? null : hostGuard.refusalOnSave(host.toString());
        if (refusal != null) {
            ctx.reject(HOST, refusal);
        }
    }
}
