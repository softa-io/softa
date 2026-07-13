package io.softa.starter.flow.runtime.task.builtin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.SendSmsConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.message.sms.dto.SendSmsDTO;

/**
 * Task executor for sending SMS via {@link MessageService}.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>Direct:</b> provide {@code phoneNumbers} and {@code content}</li>
 *   <li><b>Template:</b> provide {@code templateCode} and {@code templateVariables}</li>
 * </ul>
 * All string fields support {@code {{ expr }}} interpolation.
 * <p>
 * Only registered when {@code message-starter} is on the classpath and
 * {@link MessageService} is available as a bean.
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "SendSms",
 *   "input": {
 *     "phoneNumbers": ["{{ applicantPhone }}"],
 *     "content": "Your order {{ orderId }} has been shipped."
 *   }
 * }
 * }</pre>
 */
@Slf4j
@Component
@ConditionalOnBean(MessageService.class)
public class SendSmsTaskExecutor extends AbstractTaskExecutor {

    private final MessageService messageService;

    public SendSmsTaskExecutor(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.SEND_SMS;
    }

    @Override
    public String getExecutor() {
        return "SendSms";
    }

    @Override
    public String getName() {
        return "Send SMS";
    }

    @Override
    public String getDescription() {
        return "Send an SMS to specified phone numbers. Supports direct content or template-based sending. "
                + "All text fields support {{ expr }} interpolation.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("phoneNumbers", Map.of("type", "stringList", "label", "Phone Numbers", "required", true));
        schema.put("content", Map.of("type", "string", "label", "Message Content"));
        schema.put("templateCode", Map.of("type", "string", "label", "Template Code"));
        schema.put("templateVariables", Map.of("type", "keyValueMap", "label", "Template Variables"));
        return schema;
    }

    @Override
    public String getIcon() {
        return "smartphone";
    }

    @Override
    public int getSortOrder() {
        return 61;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        SendSmsConfig cfg = requireConfig(request, SendSmsConfig.class);
        Map<String, Object> scope = new LinkedHashMap<>(variables);

        List<String> phoneNumbers = resolveStringList(cfg.getPhoneNumbers(), scope);
        if (phoneNumbers.isEmpty()) {
            throw new IllegalArgumentException("SendSms executor requires at least one phone number in input.phoneNumbers");
        }

        // Template-based sending
        String templateCode = resolveString(cfg.getTemplateCode(), scope);
        if (templateCode != null && !templateCode.isBlank()) {
            Map<String, Object> templateVariables = cfg.getTemplateVariables() != null
                    ? resolveVariableMap(cfg.getTemplateVariables(), scope)
                    : scope;
            List<SendSmsDTO> messages = phoneNumbers.stream().map(phoneNumber -> {
                SendSmsDTO message = new SendSmsDTO();
                message.setPhoneNumber(phoneNumber);
                message.setTemplateCode(templateCode);
                message.setTemplateVariables(templateVariables);
                return message;
            }).toList();
            messageService.sendSmsBatch(messages);
            return Map.of("sent", true, "channel", "SMS", "phoneNumbers", phoneNumbers, "templateCode", templateCode);
        }

        // Direct sending
        String content = resolveString(cfg.getContent(), scope);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("SendSms executor requires input.content or input.templateCode");
        }

        List<SendSmsDTO> messages = phoneNumbers.stream().map(phoneNumber -> {
            SendSmsDTO message = new SendSmsDTO();
            message.setPhoneNumber(phoneNumber);
            message.setContent(content);
            return message;
        }).toList();
        messageService.sendSmsBatch(messages);

        return Map.of("sent", true, "channel", "SMS", "phoneNumbers", phoneNumbers);
    }
}
