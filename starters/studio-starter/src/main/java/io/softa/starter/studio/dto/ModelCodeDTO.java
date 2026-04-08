package io.softa.starter.studio.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.studio.template.enums.DesignCodeLang;

/**
 * DTO for model code file.
 */
@Data
@Schema(name = "Model Code")
public class ModelCodeDTO {

    @Schema(description = "Model name")
    private String modelName;

    @Schema(description = "Package name")
    private String packageName;

    @Schema(description = "Code language")
    private DesignCodeLang codeLang;

    @Schema(description = "Generated code files")
    private List<ModelCodeFileDTO> files = List.of();

    public ModelCodeDTO(String modelName, String packageName, DesignCodeLang codeLang) {
        this.modelName = modelName;
        this.packageName = packageName;
        this.codeLang = codeLang;
    }

    /**
     * Get the mapping of relative path file names to corresponding code text: relativeFileName -> fileContent
     */
    public Map<String, String> fileCodeMap() {
        Map<String, String> fileCodeMap = new LinkedHashMap<>();
        for (ModelCodeFileDTO file : files) {
            String relativePath = normalizeRelativePath(file.getRelativePath());
            if (relativePath.isBlank()) {
                throw new BusinessException("Generated file relativePath cannot be blank for model {0}.", modelName);
            }
            if (fileCodeMap.put(relativePath, file.getContent()) != null) {
                throw new BusinessException("Duplicate generated file path {0} for model {1}.", relativePath, modelName);
            }
        }
        return fileCodeMap;
    }

    /**
     * Get the generated file metadata for the specified relative path.
     */
    public ModelCodeFileDTO getFile(String relativePath) {
        String normalizedRelativePath = normalizeRelativePath(relativePath);
        return files.stream()
                .filter(file -> normalizeRelativePath(file.getRelativePath()).equals(normalizedRelativePath))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "The generated file {0} does not exist for model {1}.", relativePath, modelName));
    }

    private String normalizeRelativePath(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        String normalized = relativePath.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
