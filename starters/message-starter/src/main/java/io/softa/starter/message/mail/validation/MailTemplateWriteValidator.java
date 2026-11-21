package io.softa.starter.message.mail.validation;

import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.base.placeholder.TemplateBlocks;
import io.softa.framework.base.placeholder.TemplateEngine;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.message.mail.service.MailSendServerConfigService;
import io.softa.starter.message.mail.service.PlatformMailSendServerService;
import io.softa.starter.message.mail.service.PlatformMailTemplateService;
import io.softa.starter.message.template.TemplateSourceRules;
import io.softa.starter.message.template.TemplateUsage;

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
 *   <li><b>The content fits its variables and can be compiled.</b> A template that names the
 *       declared set its variables come from ({@code variablesName}) may only use what that set has
 *       ({@link TemplateSourceRules}); a name it does not have could only ever print nothing. And
 *       whatever the set, a body the template engine cannot compile is refused here rather than at
 *       the first send. Checked on create, and on an update that touches the set or the content.</li>
 *   <li><b>The set is fixed once saved.</b> Code fills a template with the set's values; a template
 *       moved to another set would be refused at every send.</li>
 * </ul>
 */
@Component
@Order(150)
public class MailTemplateWriteValidator implements ModelWriteValidator {

    static final String TENANT_MODEL = "MailTemplate";
    static final String PLATFORM_MODEL = "PlatformMailTemplate";
    static final String CODE = "code";
    static final String PREFERRED_SERVER = "preferredServerConfigId";

    static final String VARIABLES_NAME = "variablesName";
    static final String SUBJECT = "subject";
    static final String BODY_HTML = "bodyHtml";
    static final String BODY_TEXT = "bodyText";

    private static final Set<String> MODELS = Set.of(TENANT_MODEL, PLATFORM_MODEL);

    /** The columns whose change calls for the content to be checked again. */
    private static final Set<String> CONTENT = Set.of(VARIABLES_NAME, SUBJECT, BODY_HTML, BODY_TEXT);

    private final PlatformMailTemplateService platformTemplateService;

    private final MailSendServerConfigService sendServerService;

    private final PlatformMailSendServerService platformSendServerService;

    private final TemplateSourceRules sourceRules;

    public MailTemplateWriteValidator(PlatformMailTemplateService platformTemplateService,
                                      MailSendServerConfigService sendServerService,
                                      PlatformMailSendServerService platformSendServerService,
                                      TemplateSourceRules sourceRules) {
        this.platformTemplateService = platformTemplateService;
        this.sendServerService = sendServerService;
        this.platformSendServerService = platformSendServerService;
        this.sourceRules = sourceRules;
    }

    @Override
    public boolean supports(String modelName) {
        return MODELS.contains(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        checkCode(ctx);
        checkPreferredServer(ctx);
        checkContent(ctx);
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        if (ctx.patch().containsKey(CODE)) {
            checkCode(ctx);
        }
        if (ctx.patch().containsKey(PREFERRED_SERVER)) {
            checkPreferredServer(ctx);
        }
        if (ctx.patch().containsKey(VARIABLES_NAME)) {
            TemplateSourceRules.changedSetProblem(ctx.originalRow(), ctx.patch().get(VARIABLES_NAME))
                    .ifPresent(problem -> ctx.reject(VARIABLES_NAME, problem));
        }
        if (ctx.patch().keySet().stream().anyMatch(CONTENT::contains)) {
            checkContent(ctx);
        }
    }

    private void checkContent(WriteContext ctx) {
        String variablesName = text(ctx.row().get(VARIABLES_NAME));
        String bodyHtml = text(ctx.row().get(BODY_HTML));
        String bodyText = text(ctx.row().get(BODY_TEXT));
        TemplateUsage usage = TemplateUsage.ofMail(text(ctx.row().get(SUBJECT)), bodyHtml, bodyText);
        if (usage.blockError() != null) {
            ctx.reject(BODY_HTML, usage.blockError());
        } else {
            rejectUncompilable(ctx, BODY_HTML, TemplateBlocks.toPebble(bodyHtml));
        }
        rejectUncompilable(ctx, BODY_TEXT, bodyText);
        for (String problem : sourceRules.problems(variablesName, usage)) {
            ctx.reject(VARIABLES_NAME, problem);
        }
    }

    private static void rejectUncompilable(WriteContext ctx, String field, String template) {
        String problem = TemplateEngine.syntaxProblem(template);
        if (problem != null) {
            ctx.reject(field, "The template cannot be read: " + problem);
        }
    }

    private static String text(Object raw) {
        return raw == null ? null : raw.toString();
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
