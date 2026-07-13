package io.softa.starter.flow.runtime.task.builtin;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.dto.FileInfo;
import io.softa.starter.file.service.DocumentTemplateService;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.GenerateFileConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

/**
 * Task executor for generating documents via {@link DocumentTemplateService}.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>Row-based:</b> provide {@code templateId} and {@code rowId} — data is loaded from the database</li>
 *   <li><b>Data map:</b> provide {@code templateId} only — the current flow variables are used as template data</li>
 * </ul>
 * <p>
 * Only registered when {@code file-starter} is on the classpath and
 * {@link DocumentTemplateService} is available as a bean.
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "GenerateFile",
 *   "input": {
 *     "templateId": 42,
 *     "rowId": "{{ orderId }}"
 *   },
 *   "outputVariable": "generatedFile"
 * }
 * }</pre>
 */
@Slf4j
@Component
@ConditionalOnBean(DocumentTemplateService.class)
public class GenerateFileTaskExecutor extends AbstractTaskExecutor {

    private final DocumentTemplateService documentTemplateService;

    public GenerateFileTaskExecutor(DocumentTemplateService documentTemplateService) {
        this.documentTemplateService = documentTemplateService;
    }

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.GENERATE_FILE;
    }

    @Override
    public String getExecutor() {
        return "GenerateFile";
    }

    @Override
    public String getName() {
        return "Generate File";
    }

    @Override
    public String getDescription() {
        return "Generate a document from a DocumentTemplate. "
                + "Provide templateId and optionally rowId; flow variables are used as template data when rowId is absent.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("templateId", Map.of("type", "number", "label", "Template ID", "required", true));
        schema.put("rowId", Map.of("type", "string", "label", "Row ID"));
        return schema;
    }

    @Override
    public String getIcon() {
        return "file-plus";
    }

    @Override
    public int getSortOrder() {
        return 34;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        GenerateFileConfig cfg = requireConfig(request, GenerateFileConfig.class);

        Long templateId = resolveTemplateId(cfg.getTemplateId());
        // The handler no longer pre-resolves input, so interpolate the rowId placeholder here.
        String rowId = resolveString(cfg.getRowId(), variables);

        FileInfo fileInfo;
        if (rowId != null && !rowId.isBlank()) {
            log.debug("GenerateFile: rendering templateId={} with rowId={}", templateId, rowId);
            fileInfo = documentTemplateService.generateDocument(templateId, rowId);
        } else {
            log.debug("GenerateFile: rendering templateId={} with flow variables", templateId);
            fileInfo = documentTemplateService.generateDocument(templateId, variables);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileId", fileInfo.getFileId());
        result.put("fileName", fileInfo.getFileName());
        result.put("url", fileInfo.getUrl());
        if (fileInfo.getFileType() != null) {
            result.put("fileType", fileInfo.getFileType().name());
        }
        if (fileInfo.getSize() != null) {
            result.put("size", fileInfo.getSize());
        }
        return result;
    }

    private Long resolveTemplateId(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("GenerateFile executor requires input.templateId");
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String s = value.toString().trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("GenerateFile executor requires input.templateId");
        }
        return Long.parseLong(s);
    }
}
