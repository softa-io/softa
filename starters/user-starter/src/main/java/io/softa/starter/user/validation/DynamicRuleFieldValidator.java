package io.softa.starter.user.validation;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.framework.orm.service.validation.WriteValidationException;
import io.softa.starter.user.service.DynamicRoleSyncJob;

/**
 * A role's membership rule may test only the Employee fields its writer can see on every employee.
 *
 * <p>A dynamic role's members are matched across the whole tenant, past the writer's range, so the
 * query guard that keeps a condition on a sensitive field to the rows the caller sees it on cannot
 * apply. A rule such as {@code basePay > 5000} written by someone who is not shown base pay would
 * sort every employee by it, and the role's members would say who lies on which side. Judged on
 * every write of a {@code Role} — the role wizard, the generic create and update endpoints, an
 * import — at the write root, so no path writes a rule the wizard would refuse.
 *
 * <p>A rule left as it was passes on update: it was judged when it was written, and a narrower
 * manager renaming the role must not be stopped by a condition someone else wrote. Clearing the rule
 * always passes. Nothing is refused for an admin, a system context, or an application without field
 * guards (see {@link DynamicRoleSyncJob#ruleFieldsNotSeenByCaller}).
 *
 * <p>Band 100: a field rule that needs a lookup.
 */
@Component
@Order(100)
public class DynamicRuleFieldValidator implements ModelWriteValidator {

    static final String MODEL = "Role";
    static final String FIELD = "dynamicFilter";
    private static final String EMPLOYEE = "Employee";

    @Lazy
    @Autowired
    private DynamicRoleSyncJob dynamicRoleSyncJob;

    @Override
    public boolean supports(String modelName) {
        return MODEL.equals(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        check(ctx, ruleOf(ctx.row().get(FIELD)));
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        if (!ctx.patch().containsKey(FIELD)) return;
        JsonNode rule = ruleOf(ctx.patch().get(FIELD));
        JsonNode stored = ctx.originalRow() == null ? null : ruleOf(ctx.originalRow().get(FIELD));
        if (Objects.equals(rule, stored)) return;
        check(ctx, rule);
    }

    private void check(WriteContext ctx, JsonNode rule) {
        if (rule == null) return;
        Set<String> unseen = dynamicRoleSyncJob.ruleFieldsNotSeenByCaller(rule);
        if (unseen.isEmpty()) return;
        // One sentence for the rule, every field in it by label: the rule is one field of the role.
        String labels = unseen.stream()
                .map(field -> WriteValidationException.labelOf(EMPLOYEE, field))
                .collect(Collectors.joining(", "));
        ctx.reject(FIELD, "The membership rule tests " + labels + ", which you cannot see on every employee.");
    }

    /** The rule as the caller sent it or as it is stored — a JSON tree, its text, or plain lists and maps. */
    static JsonNode ruleOf(Object value) {
        if (value == null) return null;
        JsonNode node = switch (value) {
            case JsonNode json -> json;
            case String text -> text.isBlank() ? null : JsonUtils.stringToObject(text, JsonNode.class);
            default -> JsonUtils.objectToJsonNode(value);
        };
        return node == null || node.isNull() || node.isMissingNode() ? null : node;
    }
}
