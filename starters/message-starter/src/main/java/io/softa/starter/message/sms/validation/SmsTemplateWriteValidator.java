package io.softa.starter.message.sms.validation;

import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.message.template.TemplateSourceRules;
import io.softa.starter.message.template.TemplateUsage;

/**
 * An SMS template that names the set of template variables it is written against
 * ({@code variableSetName}) may only use the variables that set has — on every write path: the
 * template page, import, seed loading, a direct service call. A name the set does not have would be
 * sent to the person as the literal {@code {{ name }}}.
 *
 * <p>Checked on create, and on an update that touches the set or the content. A template that names
 * no set is not checked. The same rule as a mail template's ({@link TemplateSourceRules}), and like
 * it the set is fixed once saved; an SMS has no control flow, so there is nothing else to check.
 */
@Component
@Order(150)
public class SmsTemplateWriteValidator implements ModelWriteValidator {

    static final String MODEL = "SmsTemplate";
    static final String VARIABLE_SET_NAME = "variableSetName";
    static final String CONTENT = "content";

    private static final Set<String> CHECKED = Set.of(VARIABLE_SET_NAME, CONTENT);

    private final TemplateSourceRules sourceRules;

    public SmsTemplateWriteValidator(TemplateSourceRules sourceRules) {
        this.sourceRules = sourceRules;
    }

    @Override
    public boolean supports(String modelName) {
        return MODEL.equals(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        check(ctx);
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        if (ctx.patch().containsKey(VARIABLE_SET_NAME)) {
            TemplateSourceRules.changedSetProblem(ctx.originalRow(), ctx.patch().get(VARIABLE_SET_NAME))
                    .ifPresent(problem -> ctx.reject(VARIABLE_SET_NAME, problem));
        }
        if (ctx.patch().keySet().stream().anyMatch(CHECKED::contains)) {
            check(ctx);
        }
    }

    private void check(WriteContext ctx) {
        Object variableSetName = ctx.row().get(VARIABLE_SET_NAME);
        Object content = ctx.row().get(CONTENT);
        TemplateUsage usage = TemplateUsage.ofText(content == null ? null : content.toString());
        for (String problem : sourceRules.problems(variableSetName == null ? null : variableSetName.toString(), usage)) {
            ctx.reject(VARIABLE_SET_NAME, problem);
        }
    }
}
