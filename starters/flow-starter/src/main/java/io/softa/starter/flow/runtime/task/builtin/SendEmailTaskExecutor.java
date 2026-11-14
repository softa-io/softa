package io.softa.starter.flow.runtime.task.builtin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.service.FileService;
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
 *     "htmlBody": "<h1>Thank you!</h1>",
 *     "attachments": ["{{ invoice.fileId }}"]
 *   }
 * }
 * }</pre>
 * {@code attachments} take file ids, never links: a link in a mail is dead by the time anyone opens it.
 */
@Slf4j
@Component
@ConditionalOnBean(MessageService.class)
public class SendEmailTaskExecutor extends AbstractTaskExecutor {

    private final MessageService messageService;

    private final FileService fileService;

    public SendEmailTaskExecutor(MessageService messageService, FileService fileService) {
        this.messageService = messageService;
        this.fileService = fileService;
    }

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.SEND_EMAIL;
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
        schema.put("subject", Map.of("type", "template", "label", "Subject"));
        schema.put("textBody", Map.of("type", "template", "label", "Text Body", "multiline", true));
        schema.put("htmlBody", Map.of("type", "template", "label", "HTML Body", "multiline", true));
        schema.put("attachments", Map.of("type", "stringList", "label", "Attachments (file IDs)"));
        schema.put("templateCode", Map.of("type", "recordPicker", "label", "Template", "model", "MailTemplate",
                "valueField", "code"));
        schema.put("templateVariables", Map.of("type", "keyValueMap", "label", "Template Variables",
                "valueType", "template"));
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
        List<FileInfo> attachments = resolveAttachments(cfg.getAttachments(), scope);

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
            if (!attachments.isEmpty()) {
                // Set only when configured: an empty list here would still leave the template's own
                // attachments in place, but null says so plainly.
                message.setAttachments(attachments);
            }
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
        if (!attachments.isEmpty()) {
            dto.setAttachments(attachments);
        }

        messageService.sendMail(dto);
        return Map.of("sent", true, "channel", "EMAIL", "to", to);
    }

    /**
     * The configured attachments as FileInfo. Each item resolves to a file id or a list of them
     * ({@code 123}, {@code [123, 456]}, {@code 123,456}); anything else fails the node rather than
     * sending the mail without the file. A file id that names no file fails the node too. Only each
     * FileInfo's id travels on: the mail transport streams the content by it when the mail goes out.
     */
    private List<FileInfo> resolveAttachments(Object raw, Map<String, Object> scope) {
        Set<Long> fileIds = new LinkedHashSet<>();
        for (String item : resolveStringList(raw, scope)) {
            String[] parts = StringUtils.split(StringUtils.strip(item.trim(), "[]"), ',');
            for (String part : parts == null ? new String[0] : parts) {
                String id = part.trim();
                if (id.isEmpty()) {
                    continue;
                }
                if (!StringUtils.isNumeric(id)) {
                    throw new IllegalArgumentException(
                            "SendEmail input.attachments must hold file IDs; '" + item + "' is not one");
                }
                fileIds.add(Long.valueOf(id));
            }
        }
        return fileIds.isEmpty() ? List.of() : fileService.getByFileIds(new ArrayList<>(fileIds));
    }
}
