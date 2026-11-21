package io.softa.starter.message.mail.support;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.starter.message.mail.entity.AbstractMailTemplate;
import io.softa.starter.message.mail.service.MailTemplateService;
import io.softa.starter.message.mail.service.PlatformMailTemplateService;
import io.softa.starter.message.shared.TenantScopes;

/**
 * Finds the template a send names, in the tier its scope names: the tenant's for {@code TENANT}, the
 * platform's for {@code PLATFORM}. {@code templateId} addresses the exact row (editor test sends: what
 * you preview is what goes out, enabled or not); {@code templateCode} resolves enabled rows only. A
 * code that lives in the other tier fails with a message saying so — one code, one tier, no fallback.
 * With multi-tenancy off there is one owner: the deployment's own templates, then the platform
 * table's.
 *
 * <p>One lookup for the two moments that need it: accepting a send filled from a variable set, whose
 * set is checked against the template's there, and the first delivery attempt, which renders.
 */
@Component
public class MailTemplateLocator {

    private final MailTemplateService templateService;

    private final PlatformMailTemplateService platformTemplateService;

    public MailTemplateLocator(MailTemplateService templateService,
                               PlatformMailTemplateService platformTemplateService) {
        this.templateService = templateService;
        this.platformTemplateService = platformTemplateService;
    }

    /**
     * The template, or null when the send names none.
     *
     * @throws BusinessException when the named template cannot be found in the scope's tier
     */
    public AbstractMailTemplate find(Long id, String code, MessageScope scope) {
        if (id == null && !StringUtils.hasText(code)) {
            return null;
        }
        if (!TenantScopes.multiTenancyEnabled()) {
            return findSingleOwner(id, code);
        }
        if (scope == MessageScope.PLATFORM) {
            if (id != null) {
                return platformTemplateService.getRequiredById(id);
            }
            try {
                return platformTemplateService.resolve(code);
            } catch (BusinessException notPlatform) {
                throw templateService.anyTenantHasCode(code) ? tierMismatch(code, scope) : notPlatform;
            }
        }
        if (id != null) {
            return templateService.getRequiredById(id);
        }
        try {
            return templateService.resolve(code);
        } catch (BusinessException notTenant) {
            throw platformTemplateService.findAnyByCode(code).isPresent() ? tierMismatch(code, scope) : notTenant;
        }
    }

    /** One owner (multi-tenancy off): the deployment's own template, then the platform table's. */
    private AbstractMailTemplate findSingleOwner(Long id, String code) {
        if (id != null) {
            return templateService.getById(id).<AbstractMailTemplate>map(t -> t)
                    .orElseGet(() -> platformTemplateService.getRequiredById(id));
        }
        try {
            return templateService.resolve(code);
        } catch (BusinessException notOwn) {
            return platformTemplateService.resolve(code);
        }
    }

    private static BusinessException tierMismatch(String code, MessageScope scope) {
        return new BusinessException(scope == MessageScope.PLATFORM
                ? "Mail template ''{0}'' is a tenant template, not a platform one: send it with scope TENANT."
                : "Mail template ''{0}'' is a platform template, not a tenant one: send it with scope PLATFORM.",
                code);
    }
}
