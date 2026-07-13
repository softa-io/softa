package io.softa.starter.flow.runtime.task.builtin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.SendInboxNotificationConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;
import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.inbox.enums.NotificationType;
import io.softa.starter.message.service.MessageService;

/**
 * Task executor for sending in-app notifications via {@link MessageService}.
 * <p>
 * All string fields support {@code {{ expr }}} interpolation.
 * <p>
 * Only registered when {@code message-starter} is on the classpath and
 * {@link MessageService} is available as a bean.
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "SendInboxNotification",
 *   "input": {
 *     "recipientIds": "{{ approverIds }}",
 *     "title": "New approval request",
 *     "content": "Order {{ orderId }} requires your attention."
 *   }
 * }
 * }</pre>
 */
@Slf4j
@Component
@ConditionalOnBean(MessageService.class)
public class SendInboxNotificationTaskExecutor extends AbstractTaskExecutor {

    private final MessageService messageService;

    public SendInboxNotificationTaskExecutor(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.SEND_INBOX_NOTIFICATION;
    }

    @Override
    public String getExecutor() {
        return "SendInboxNotification";
    }

    @Override
    public String getName() {
        return "Send Inbox Notification";
    }

    @Override
    public String getDescription() {
        return "Send an in-app notification to specified recipients. "
                + "All text fields support {{ expr }} interpolation.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("recipientIds", Map.of("type", "userPicker", "label", "Recipients", "multiple", true, "required", true));
        schema.put("title", Map.of("type", "string", "label", "Title", "required", true));
        schema.put("content", Map.of("type", "string", "label", "Content", "required", true));
        schema.put("notificationType", Map.of("type", "enum", "label", "Notification Type",
                "options", List.of("SYSTEM", "WORKFLOW", "MANUAL"), "default", "WORKFLOW"));
        return schema;
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        return Map.of("notificationType", "WORKFLOW");
    }

    @Override
    public String getIcon() {
        return "bell";
    }

    @Override
    public int getSortOrder() {
        return 62;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        SendInboxNotificationConfig cfg = requireConfig(request, SendInboxNotificationConfig.class);
        Map<String, Object> scope = new LinkedHashMap<>(variables);

        List<Long> recipientIds = resolveLongList(cfg.getRecipientIds(), scope);
        if (recipientIds.isEmpty()) {
            throw new IllegalArgumentException("SendInboxNotification executor requires at least one recipient in input.recipientIds");
        }

        String title = requireResolved(cfg.getTitle(), "title", scope);
        String content = requireResolved(cfg.getContent(), "content", scope);

        String typeStr = resolveString(cfg.getNotificationType(), scope);
        NotificationType notificationType = NotificationType.WORKFLOW;
        if (typeStr != null && !typeStr.isBlank()) {
            notificationType = NotificationType.valueOf(typeStr.toUpperCase());
        }

        NotificationType finalType = notificationType;
        List<SendInboxDTO> messages = recipientIds.stream().map(recipientId -> {
            SendInboxDTO message = new SendInboxDTO();
            message.setRecipientId(recipientId);
            message.setTitle(title);
            message.setContent(content);
            message.setNotificationType(finalType);
            return message;
        }).toList();
        messageService.sendInboxBatch(messages);

        return Map.of("sent", true, "channel", "INBOX", "recipientCount", recipientIds.size());
    }

    private String requireResolved(String value, String key, Map<String, Object> scope) {
        if (value == null) {
            throw new IllegalArgumentException("SendInboxNotification executor requires input." + key);
        }
        String resolved = resolveString(value, scope);
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException("SendInboxNotification executor requires non-blank input." + key);
        }
        return resolved;
    }

    private List<Long> resolveLongList(Object value, Map<String, Object> scope) {
        switch (value) {
            case null -> {
                return List.of();
            }
            case List<?> list -> {
                List<Long> result = new ArrayList<>();
                for (Object item : list) {
                    Long id = asLong(item);
                    if (id != null) {
                        result.add(id);
                    }
                }
                return result;
            }

            // Single value or expression that resolves to a list
            case String s when s.contains("{{") -> {
                Object resolved = ComputeUtils.execute(s.replaceAll("^\\{\\{\\s*|\\s*}}$", ""), new LinkedHashMap<>(scope));
                if (resolved instanceof List<?> list) {
                    List<Long> result = new ArrayList<>();
                    for (Object item : list) {
                        Long id = asLong(item);
                        if (id != null) result.add(id);
                    }
                    return result;
                }
                Long single = asLong(resolved);
                return single != null ? List.of(single) : List.of();
            }
            default -> {
            }
        }
        Long single = asLong(value);
        return single != null ? List.of(single) : List.of();
    }

    private Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
