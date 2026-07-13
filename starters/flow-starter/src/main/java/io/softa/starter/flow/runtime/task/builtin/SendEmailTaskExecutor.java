package io.softa.starter.flow.runtime.task.builtin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.SendEmailConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.enums.MailPriority;
import io.softa.starter.message.service.MessageService;

/**
 * Task executor for sending emails via {@link MessageService}.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>Direct:</b> provide {@code to}, {@code subject}, and {@code textBody}/{@code htmlBody}</li>
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
 *   "executor": "SendEmail",
 *   "input": {
 *     "to": ["{{ applicantEmail }}"],
 *     "subject": "Order {{ orderId }} confirmed",
 *     "htmlBody": "<h1>Thank you!</h1>"
 *   }
 * }
 * }</pre>
 */
@Slf4j
@Component
@ConditionalOnBean(MessageService.class)
public class SendEmailTaskExecutor extends AbstractTaskExecutor {

    private final MessageService messageService;

    public SendEmailTaskExecutor(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.SEND_EMAIL;
    }

    @Override
    public String getExecutor() {
        return "SendEmail";
    }

    @Override
    public String getName() {
        return "Send Email";
    }

    @Override
    public String getDescription() {
        return "Send an email to specified recipients. Supports direct content or template-based sending. "
                + "All text fields support {{ expr }} interpolation.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("to", Map.of("type", "stringList", "label", "To (Recipients)", "required", true));
        schema.put("cc", Map.of("type", "stringList", "label", "CC"));
        schema.put("bcc", Map.of("type", "stringList", "label", "BCC"));
        schema.put("subject", Map.of("type", "string", "label", "Subject"));
        schema.put("textBody", Map.of("type", "string", "label", "Text Body"));
        schema.put("htmlBody", Map.of("type", "richText", "label", "HTML Body"));
        schema.put("templateCode", Map.of("type", "string", "label", "Template Code"));
        schema.put("templateVariables", Map.of("type", "keyValueMap", "label", "Template Variables"));
        schema.put("priority", Map.of("type", "enum", "label", "Priority",
                "options", List.of("HIGH", "NORMAL", "LOW"), "default", "NORMAL"));
        return schema;
    }

    @Override
    public String getIcon() {
        return "mail";
    }

    @Override
    public int getSortOrder() {
        return 60;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        SendEmailConfig cfg = requireConfig(request, SendEmailConfig.class);
        Map<String, Object> scope = new LinkedHashMap<>(variables);

        List<String> to = resolveStringList(cfg.getTo(), scope);
        if (to.isEmpty()) {
            throw new IllegalArgumentException("SendEmail executor requires at least one recipient in input.to");
        }

        // Template-based sending
        String templateCode = resolveString(cfg.getTemplateCode(), scope);
        if (templateCode != null && !templateCode.isBlank()) {
            Map<String, Object> templateVariables = cfg.getTemplateVariables() != null
                    ? resolveVariableMap(cfg.getTemplateVariables(), scope)
                    : scope;
            SendMailDTO message = new SendMailDTO();
            message.setTo(to);
            message.setTemplateCode(templateCode);
            message.setTemplateVariables(templateVariables);
            messageService.sendMail(message);
            return Map.of("sent", true, "channel", "EMAIL", "to", to, "templateCode", templateCode);
        }

        // Direct sending
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(to);
        dto.setCc(resolveStringList(cfg.getCc(), scope));
        dto.setBcc(resolveStringList(cfg.getBcc(), scope));
        dto.setSubject(resolveString(cfg.getSubject(), scope));
        dto.setTextBody(resolveString(cfg.getTextBody(), scope));
        dto.setHtmlBody(resolveString(cfg.getHtmlBody(), scope));

        String priority = resolveString(cfg.getPriority(), scope);
        if (priority != null && !priority.isBlank()) {
            dto.setPriority(MailPriority.valueOf(priority.toUpperCase()));
        }

        messageService.sendMail(dto);
        return Map.of("sent", true, "channel", "EMAIL", "to", to);
    }
}
