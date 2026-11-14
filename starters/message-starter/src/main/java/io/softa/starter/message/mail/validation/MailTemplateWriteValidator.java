package io.softa.starter.message.mail.validation;

import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.message.mail.service.MailSendServerConfigService;
import io.softa.starter.message.mail.service.PlatformMailSendServerService;
import io.softa.starter.message.mail.service.PlatformMailTemplateService;

/**
 * The two template rules no field declaration can express, on every write path (the template pages,
 * import, seed loading, a direct service call):
 * <ul>
 *   <li><b>One code, one tier.</b> A tenant template may not take a code the platform uses for one of
 *       its templates: a send names its tier with its scope, and a code living in both would render
 *       differently depending on a flag the caller may get wrong. The platform's template table is the
 *       list of platform codes.</li>
 *   <li><b>A pinned send server is the template's own owner's.</b> A tenant template pins one of its
 *       tenant's send servers, a platform template one of the platform's — the two tables never mix.
 *       A tenant's server is looked up within the writing tenant, so another tenant's id is "not
 *       found".</li>
 * </ul>
 */
@Component
@Order(150)
public class MailTemplateWriteValidator implements ModelWriteValidator {

    static final String TENANT_MODEL = "MailTemplate";
    static final String PLATFORM_MODEL = "PlatformMailTemplate";
    static final String CODE = "code";
    static final String PREFERRED_SERVER = "preferredServerConfigId";

    private static final Set<String> MODELS = Set.of(TENANT_MODEL, PLATFORM_MODEL);

    private final PlatformMailTemplateService platformTemplateService;

    private final MailSendServerConfigService sendServerService;

    private final PlatformMailSendServerService platformSendServerService;

    public MailTemplateWriteValidator(PlatformMailTemplateService platformTemplateService,
                                      MailSendServerConfigService sendServerService,
                                      PlatformMailSendServerService platformSendServerService) {
        this.platformTemplateService = platformTemplateService;
        this.sendServerService = sendServerService;
        this.platformSendServerService = platformSendServerService;
    }

    @Override
    public boolean supports(String modelName) {
        return MODELS.contains(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        checkCode(ctx);
        checkPreferredServer(ctx);
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        if (ctx.patch().containsKey(CODE)) {
            checkCode(ctx);
        }
        if (ctx.patch().containsKey(PREFERRED_SERVER)) {
            checkPreferredServer(ctx);
        }
    }

    private void checkCode(WriteContext ctx) {
        if (!TENANT_MODEL.equals(ctx.modelName())) {
            return;
        }
        Object code = ctx.row().get(CODE);
        if (code instanceof String text && !text.isBlank()
                && platformTemplateService.findAnyByCode(text.trim()).isPresent()) {
            ctx.reject(CODE, "The code " + text.trim() + " belongs to a platform template. "
                    + "A tenant template needs a code of its own.");
        }
    }

    private void checkPreferredServer(WriteContext ctx) {
        Long serverId = asId(ctx.row().get(PREFERRED_SERVER));
        if (serverId == null) {
            return;
        }
        boolean platform = PLATFORM_MODEL.equals(ctx.modelName());
        boolean found = platform
                ? platformSendServerService.getById(serverId).isPresent()
                : sendServerService.getById(serverId).isPresent();
        if (!found) {
            ctx.reject(PREFERRED_SERVER, platform
                    ? "Send server " + serverId + " is not one of the platform's send servers."
                    : "Send server " + serverId + " is not one of this tenant's send servers. "
                            + "A template may only pin a send server of its own.");
        }
    }

    private static Long asId(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1L;   // not an id at all: reported as a server that does not exist
        }
    }
}
