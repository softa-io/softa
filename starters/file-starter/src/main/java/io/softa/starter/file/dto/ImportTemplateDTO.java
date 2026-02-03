package io.softa.starter.file.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;

import io.softa.starter.file.enums.ImportRule;

@Data
@NoArgsConstructor
public class ImportTemplateDTO {

    private String modelName;

    private ImportRule importRule;

    private List<String> uniqueConstraints;

    private Boolean ignoreEmpty;

    private Boolean skipException;

    private String customHandler;

    private Map<String, Object> env;

    private List<ImportFieldDTO> importFields;

    // file info
    private String templateId;
    private String fileId;
    private String historyId;
    private String fileName;

    public void addImportField(ImportFieldDTO importFieldDTO) {
        if (CollectionUtils.isEmpty(importFields)) {
            this.importFields = new ArrayList<>();
        }
        this.importFields.add(importFieldDTO);
    }
}