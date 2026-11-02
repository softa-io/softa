package io.softa.starter.file.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.context.Context;
import io.softa.starter.file.enums.ImportRule;

@Data
@NoArgsConstructor
public class ImportTemplateDTO {

    /**
     * The caller's context, carried across the MQ hop so the consumer can restore it.
     *
     * <p>An import runs entirely on framework reads and writes — tenant isolation, relation
     * lookups, audit stamping — and a Pulsar listener thread has no ambient context. Without
     * this the consumer runs with a blank one: {@code WhereBuilder.handleMultiTenant} then
     * emits {@code tenant_id = NULL}, which matches no row, so every relation lookup fails
     * ("Cannot find LegalEntity by code=…") while the data sits right there, and anything
     * written gets a null tenantId. The {@code @Async} path does not need this — {@code
     * AsyncConfig}'s TaskDecorator ({@code ContextHolder::wrap}) already clones the context
     * onto the pool thread — but the MQ path crosses a process boundary, so it must travel
     * in the message. Same shape as {@code CronTaskMessage.context}.
     */
    private Context context;

    private String modelName;

    private ImportRule importRule;

    private List<String> uniqueConstraints;

    private Boolean ignoreEmpty;

    private Boolean skipException;

    private String customHandler;

    private Map<String, Object> env;

    private List<ImportFieldDTO> importFields;

    /**
     * The template's columns the caller may not write ({@link io.softa.starter.file.excel.imports.ImportFieldAccess}),
     * taken out of {@link #importFields}. Decided where the caller's permissions are known and carried
     * here, because an asynchronous import reads the file on a consumer that has no such caller: there
     * a value in one of these columns is refused, row by row, and the column is otherwise ignored.
     */
    private List<ImportFieldDTO> withheldFields = new ArrayList<>();

    // file info
    private Long templateId;
    private Long fileId;
    private Long historyId;
    private String fileName;

    public void addImportField(ImportFieldDTO importFieldDTO) {
        if (CollectionUtils.isEmpty(importFields)) {
            this.importFields = new ArrayList<>();
        }
        this.importFields.add(importFieldDTO);
    }
}