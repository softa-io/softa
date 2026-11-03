package io.softa.starter.flow.compiler.validation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.util.StringUtils;

import io.softa.framework.orm.utils.BeanTool;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.NodeConfigParser;
import io.softa.starter.flow.runtime.nodeconfig.AsyncTaskConfig;
import io.softa.starter.flow.runtime.nodeconfig.CallServiceConfig;
import io.softa.starter.flow.runtime.nodeconfig.CreateDataConfig;
import io.softa.starter.flow.runtime.nodeconfig.DeleteDataConfig;
import io.softa.starter.flow.runtime.nodeconfig.ExtractTransformConfig;
import io.softa.starter.flow.runtime.nodeconfig.GenerateFileConfig;
import io.softa.starter.flow.runtime.nodeconfig.GetDataConfig;
import io.softa.starter.flow.runtime.nodeconfig.QueryAiConfig;
import io.softa.starter.flow.runtime.nodeconfig.QueryRecordsConfig;
import io.softa.starter.flow.runtime.nodeconfig.SendEmailConfig;
import io.softa.starter.flow.runtime.nodeconfig.SendInboxNotificationConfig;
import io.softa.starter.flow.runtime.nodeconfig.SendSmsConfig;
import io.softa.starter.flow.runtime.nodeconfig.TaskConfigTypes;
import io.softa.starter.flow.runtime.nodeconfig.TaskNodeConfig;
import io.softa.starter.flow.runtime.nodeconfig.UpdateDataConfig;
import io.softa.starter.flow.runtime.nodeconfig.ValidateDataConfig;
import io.softa.starter.flow.runtime.nodeconfig.WebHookConfig;

/**
 * Validates task node config at compile (publish) time.
 * <p>
 * Two layers: (1) config presence for the message/service node types that require it, and
 * (2) shape validation for the <em>typed</em> node types (those in {@link TaskConfigTypes}) —
 * their raw input is parsed into the executor's config DTO and its required fields are checked,
 * so a misconfigured node fails at publish instead of mid-execution. The executor is otherwise
 * fully determined by {@link FlowNodeType}.
 */
public class TaskConfigValidator implements FlowValidator {

    private static final String MISSING_TASK_CONFIG = "MISSING_TASK_CONFIG";
    private static final String INVALID_TASK_CONFIG = "INVALID_TASK_CONFIG";
    private static final String MISSING_REQUIRED_INPUT = "MISSING_REQUIRED_INPUT";
    private static final String ARG_TYPES_ARITY = "ARG_TYPES_ARITY";

    private static final Set<FlowNodeType> CONFIG_REQUIRED_NODE_TYPES = EnumSet.of(
            FlowNodeType.CALL_SERVICE,
            FlowNodeType.CALL_WEBHOOK,
            FlowNodeType.SEND_EMAIL,
            FlowNodeType.SEND_SMS,
            FlowNodeType.SEND_INBOX_NOTIFICATION,
            FlowNodeType.ASYNC_TASK
    );

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> d = new ArrayList<>();
        for (FlowGraphNode node : context.nodeMap().values()) {
            validateConfigPresence(node, d);
            validateTypedConfig(node, d);
        }
        return d;
    }

    private void validateConfigPresence(FlowGraphNode node, List<CompileDiagnostic> d) {
        if (!CONFIG_REQUIRED_NODE_TYPES.contains(node.getType())) {
            return;
        }
        if (node.getConfig() == null || node.getConfig().isEmpty()) {
            d.add(CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(), MISSING_TASK_CONFIG,
                    "Task node '" + node.getId() + "' of type '" + node.getType().getType()
                            + "' must define config"));
        }
    }

    /** Parse a typed node type's raw input into its DTO and check required fields / arg-type arity. */
    private void validateTypedConfig(FlowGraphNode node, List<CompileDiagnostic> d) {
        Class<?> configType = TaskConfigTypes.forNodeType(node.getType());
        if (configType == null) {
            return;
        }
        Map<String, Object> input = extractInput(node);
        if (input == null) {
            // Absent config is reported by validateConfigPresence (for the required-config types).
            return;
        }
        String at = inputPath(node);
        Object dto;
        try {
            dto = BeanTool.objectToObject(input, configType);
        } catch (RuntimeException e) {
            d.add(CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(), INVALID_TASK_CONFIG,
                    "Task node '" + node.getId() + "' has malformed "
                            + at.substring(0, at.length() - 1) + ": " + e.getMessage()));
            return;
        }
        switch (dto) {
            case CreateDataConfig c -> {
                requireText(node, at + "modelName", c.getModelName(), d);
                requireMap(node, at + "rowTemplate", c.getRowTemplate(), d);
            }
            case UpdateDataConfig u -> {
                requireText(node, at + "modelName", u.getModelName(), d);
                requireMap(node, at + "rowTemplate", u.getRowTemplate(), d);
            }
            case GetDataConfig g -> {
                requireText(node, at + "modelName", g.getModelName(), d);
                requireText(node, at + "getDataType", g.getGetDataType(), d);
            }
            case DeleteDataConfig del -> requireText(node, at + "modelName", del.getModelName(), d);
            case QueryRecordsConfig q -> requireText(node, at + "modelName", q.getModelName(), d);
            case CallServiceConfig cs -> {
                requireText(node, at + "beanName", cs.getBeanName(), d);
                requireText(node, at + "methodName", cs.getMethodName(), d);
                validateArgTypesArity(node, at, cs, d);
            }
            case SendSmsConfig sms -> {
                requirePresent(node, at + "phoneNumbers", sms.getPhoneNumbers(), d);
                if (!StringUtils.hasText(sms.getContent()) && !StringUtils.hasText(sms.getTemplateCode())) {
                    d.add(CompileDiagnostic.fieldLevel(node.getId(), node.getType().getType(),
                            at + "content", MISSING_REQUIRED_INPUT, "Task node '" + node.getId()
                                    + "' requires " + at + "content or " + at + "templateCode"));
                }
            }
            case SendEmailConfig mail -> requirePresent(node, at + "to", mail.getTo(), d);
            case SendInboxNotificationConfig inbox -> {
                requirePresent(node, at + "recipientIds", inbox.getRecipientIds(), d);
                requireText(node, at + "title", inbox.getTitle(), d);
                requireText(node, at + "content", inbox.getContent(), d);
            }
            case GenerateFileConfig file -> requirePresent(node, at + "templateId", file.getTemplateId(), d);
            case ValidateDataConfig v -> {
                requireText(node, at + "expression", v.getExpression(), d);
                requireText(node, at + "exceptionMsg", v.getExceptionMsg(), d);
            }
            case ExtractTransformConfig t -> {
                requireText(node, at + "collectionVariable", t.getCollectionVariable(), d);
                requireText(node, at + "itemKey", t.getItemKey(), d);
            }
            case QueryAiConfig ai -> requireText(node, at + "queryContent", ai.getQueryContent(), d);
            case WebHookConfig hook -> requireText(node, at + "url", hook.getUrl(), d);
            case AsyncTaskConfig async ->
                    requireText(node, at + "asyncTaskHandlerCode", async.getAsyncTaskHandlerCode(), d);
            default -> { /* no DTO-specific rules */ }
        }
    }

    /**
     * Where a task node's input fields sit, for the diagnostics: under {@code input} when the design nests them, else
     * at the top level beside the node's own keys, as the designer writes them — in either case inside {@code task}
     * when the config wraps itself in one.
     */
    private static String inputPath(FlowGraphNode node) {
        Map<?, ?> config = node.getConfig();
        String base = "config.";
        if (config != null && config.get("task") instanceof Map<?, ?> task) {
            config = task;
            base = "config.task.";
        }
        return config != null && config.get("input") instanceof Map<?, ?> ? base + "input." : base;
    }

    private Map<String, Object> extractInput(FlowGraphNode node) {
        Object parsed = NodeConfigParser.parse(node.getType(), node.getConfig());
        return parsed instanceof TaskNodeConfig tnc ? tnc.getInput() : null;
    }

    private void validateArgTypesArity(FlowGraphNode node, String at, CallServiceConfig cs, List<CompileDiagnostic> d) {
        if (cs.getArgTypes() == null) {
            return;
        }
        int argCount = cs.getArgs() == null ? 0 : cs.getArgs().size();
        if (cs.getArgTypes().size() != argCount) {
            d.add(CompileDiagnostic.fieldLevel(node.getId(), node.getType().getType(), at + "argTypes",
                    ARG_TYPES_ARITY, "Task node '" + node.getId() + "' argTypes length ("
                            + cs.getArgTypes().size() + ") must match args length (" + argCount + ")"));
        }
    }

    private void requireText(FlowGraphNode node, String field, String value, List<CompileDiagnostic> d) {
        if (!StringUtils.hasText(value)) {
            d.add(CompileDiagnostic.fieldLevel(node.getId(), node.getType().getType(), field,
                    MISSING_REQUIRED_INPUT, "Task node '" + node.getId() + "' requires " + field));
        }
    }

    private void requireMap(FlowGraphNode node, String field, Map<?, ?> value, List<CompileDiagnostic> d) {
        if (value == null || value.isEmpty()) {
            d.add(CompileDiagnostic.fieldLevel(node.getId(), node.getType().getType(), field,
                    MISSING_REQUIRED_INPUT, "Task node '" + node.getId() + "' requires " + field));
        }
    }

    /**
     * Presence check for polymorphic fields (a literal list, or a {@code {{ expr }}} string
     * resolving to one at runtime): null, a blank string, and an empty collection all fail.
     */
    private void requirePresent(FlowGraphNode node, String field, Object value, List<CompileDiagnostic> d) {
        boolean present = switch (value) {
            case null -> false;
            case String s -> StringUtils.hasText(s);
            case java.util.Collection<?> c -> !c.isEmpty();
            default -> true;
        };
        if (!present) {
            d.add(CompileDiagnostic.fieldLevel(node.getId(), node.getType().getType(), field,
                    MISSING_REQUIRED_INPUT, "Task node '" + node.getId() + "' requires " + field));
        }
    }
}
