package io.softa.starter.studio.template.generator;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.placeholder.TemplateEngine;
import io.softa.framework.base.utils.MapUtils;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.utils.BeanTool;
import io.softa.starter.studio.dto.ModelCodeDTO;
import io.softa.starter.studio.dto.ModelCodeFileDTO;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.template.entity.DesignCodeTemplate;
import io.softa.starter.studio.template.entity.DesignFieldCodeMapping;
import io.softa.starter.studio.template.enums.DesignCodeLang;

/**
 * Code generator using Pebble template engine.
 */
@Component
public class CodeGenerator {

    private static final String TEMPLATE_DIR = "templates/code/";
    private static final String TEMPLATE_PATTERN = "classpath*:" + TEMPLATE_DIR + "**/*.peb";
    private final DesignGenerationMetadataResolver metadataResolver;
    private final PathMatchingResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();

    public CodeGenerator(DesignGenerationMetadataResolver metadataResolver) {
        this.metadataResolver = metadataResolver;
    }

    /**
     * Set additional parameters: username, current date.
     *
     * @return additional parameters
     */
    private static Map<String, Object> getAdditionalParams() {
        Context context = ContextHolder.getContext();
        return MapUtils.strObj()
                .put("userName", context.getName())
                .put("currentDate", LocalDate.now())
                .build();
    }

    /**
     * Resolve the current Java fallback type for a field based on its FieldType.
     */
    private static String resolveFallbackJavaType(Map<String, Object> fieldMap) {
        Object fieldTypeValue = fieldMap.get("fieldType");
        FieldType fieldType;
        if (fieldTypeValue instanceof FieldType ft) {
            fieldType = ft;
        } else {
            fieldType = FieldType.of(String.valueOf(fieldTypeValue));
        }
        return switch (fieldType) {
            case ONE_TO_MANY -> "List<" + fieldMap.getOrDefault("relatedModel", "Object") + ">";
            case MANY_TO_MANY, MULTI_FILE -> "List<Long>";
            case OPTION -> resolveOptionSetJavaType(fieldMap, false);
            case MULTI_OPTION -> resolveOptionSetJavaType(fieldMap, true);
            case MULTI_STRING -> "List<String>";
            case DTO -> "JsonNode";
            default -> fieldType.getJavaType().getSimpleName();
        };
    }

    /**
     * Resolve Option and MultiOption field Java types from optionSetCode.
     * Falls back to String/List<String> when the code is absent.
     */
    private static String resolveOptionSetJavaType(Map<String, Object> fieldMap, boolean multiple) {
        Object optionSetCodeValue = fieldMap.get("optionSetCode");
        if (optionSetCodeValue instanceof String optionSetCode && !optionSetCode.isBlank()) {
            return multiple ? "List<" + optionSetCode + ">" : optionSetCode;
        }
        return multiple ? "List<String>" : FieldType.OPTION.getJavaType().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private void enrichFieldCodeTypes(Map<String, Object> modelData,
                                      DesignCodeLang requestedCodeLang,
                                      Map<FieldType, DesignFieldCodeMapping> codeMappings) {
        Object fieldsObj = modelData.get("modelFields");
        if (fieldsObj instanceof List<?> fields) {
            for (Object field : fields) {
                if (field instanceof Map<?, ?> fieldMap) {
                    Map<String, Object> typedFieldMap = (Map<String, Object>) fieldMap;
                    typedFieldMap.put("javaType", resolveCodeType(typedFieldMap, requestedCodeLang, codeMappings));
                }
            }
        }
    }

    private String resolveCodeType(Map<String, Object> fieldMap,
                                   DesignCodeLang requestedCodeLang,
                                   Map<FieldType, DesignFieldCodeMapping> codeMappings) {
        Object fieldTypeValue = fieldMap.get("fieldType");
        FieldType fieldType = fieldTypeValue instanceof FieldType ft ? ft : FieldType.of(String.valueOf(fieldTypeValue));
        DesignFieldCodeMapping mapping = codeMappings.get(fieldType);
        if (mapping != null && StringUtils.hasText(mapping.getPropertyType())) {
            return TemplateEngine.render(mapping.getPropertyType(), MapUtils.strObj()
                    .put("field", fieldMap)
                    .put("codeLang", requestedCodeLang)
                    .build()).trim();
        }
        return resolveFallbackJavaType(fieldMap);
    }

    private List<CodeTemplateSpec> resolveCodeTemplates(DesignCodeLang codeLang, boolean databaseTemplateConfigured) {
        if (databaseTemplateConfigured) {
            return metadataResolver.getCodeTemplates(codeLang).stream()
                    .map(this::toCodeTemplateSpec)
                    .toList();
        }
        return loadClasspathCodeTemplates();
    }

    private CodeTemplateSpec toCodeTemplateSpec(DesignCodeTemplate template) {
        return new CodeTemplateSpec(template.getId(),
                StringUtils.hasText(template.getFileName()) ? template.getFileName() : "template-" + template.getId(),
                template.getSequence(),
                template.getSubDirectory(),
                template.getFileName(),
                null,
                template.getTemplateContent());
    }

    private List<CodeTemplateSpec> loadClasspathCodeTemplates() {
        try {
            List<String> relativeTemplatePaths = Arrays.stream(resourcePatternResolver.getResources(TEMPLATE_PATTERN))
                    .map(this::extractRelativeTemplatePath)
                    .sorted(Comparator.comparingInt(this::pathDepth).thenComparing(path -> path))
                    .toList();
            List<CodeTemplateSpec> codeTemplates = new ArrayList<>(relativeTemplatePaths.size());
            for (int i = 0; i < relativeTemplatePaths.size(); i++) {
                String relativeTemplatePath = relativeTemplatePaths.get(i);
                String renderedPathTemplate = relativeTemplatePath.substring(0, relativeTemplatePath.length() - ".peb".length());
                int lastSeparator = renderedPathTemplate.lastIndexOf('/');
                String subDirectory = lastSeparator >= 0 ? renderedPathTemplate.substring(0, lastSeparator) : "";
                String fileName = lastSeparator >= 0 ? renderedPathTemplate.substring(lastSeparator + 1) : renderedPathTemplate;
                codeTemplates.add(new CodeTemplateSpec(null,
                        relativeTemplatePath,
                        i + 1,
                        subDirectory,
                        fileName,
                        TEMPLATE_DIR + relativeTemplatePath,
                        null));
            }
            return codeTemplates;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to load code templates from {0}: {1}", TEMPLATE_DIR, e.getMessage());
        }
    }

    private String extractRelativeTemplatePath(Resource resource) {
        try {
            String resourcePath = URLDecoder.decode(resource.getURL().toExternalForm(), StandardCharsets.UTF_8);
            int markerIndex = resourcePath.lastIndexOf(TEMPLATE_DIR);
            if (markerIndex < 0) {
                throw new IllegalArgumentException("The template resource path {0} is not under {1}.", resourcePath, TEMPLATE_DIR);
            }
            return resourcePath.substring(markerIndex + TEMPLATE_DIR.length());
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve the template resource path for {0}: {1}",
                    resource.getDescription(), e.getMessage());
        }
    }

    private int pathDepth(String path) {
        return (int) path.chars().filter(ch -> ch == '/').count();
    }

    private String renderTemplate(CodeTemplateSpec template, Map<String, Object> modelData) {
        if (template.classpathTemplatePath() != null) {
            return TemplateEngine.renderFilePath(template.classpathTemplatePath(), modelData);
        }
        return TemplateEngine.render(Objects.toString(template.templateContent(), ""), modelData);
    }

    private ModelCodeFileDTO renderCodeFile(CodeTemplateSpec template, Map<String, Object> modelData) {
        String subDirectory = renderSubDirectory(template.subDirectoryTemplate(), modelData);
        String fileName = renderFileName(template.fileNameTemplate(), modelData);
        ModelCodeFileDTO file = new ModelCodeFileDTO();
        file.setTemplateId(template.templateId());
        file.setTemplateName(template.templateName());
        file.setSequence(template.sequence());
        file.setSubDirectory(subDirectory);
        file.setFileName(fileName);
        file.setRelativePath(buildRelativePath(subDirectory, fileName));
        file.setContent(renderTemplate(template, modelData));
        return file;
    }

    private String renderSubDirectory(String subDirectoryTemplate, Map<String, Object> modelData) {
        if (!StringUtils.hasText(subDirectoryTemplate)) {
            return "";
        }
        return normalizeSubDirectory(TemplateEngine.render(subDirectoryTemplate, modelData));
    }

    private String normalizeSubDirectory(String subDirectory) {
        if (!StringUtils.hasText(subDirectory)) {
            return "";
        }
        String normalized = subDirectory.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        normalized = normalized.replaceAll("/+", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!StringUtils.hasText(normalized) || ".".equals(normalized)) {
            return "";
        }
        return normalized;
    }

    private String renderFileName(String fileNameTemplate, Map<String, Object> modelData) {
        String fileName = StringUtils.hasText(fileNameTemplate)
                ? TemplateEngine.render(fileNameTemplate, modelData)
                : "";
        String normalized = fileName.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("The rendered fileName cannot be blank.");
        }
        if (normalized.contains("/")) {
            throw new IllegalArgumentException("The rendered fileName {0} cannot contain directory separators.", normalized);
        }
        return normalized;
    }

    private String buildRelativePath(String subDirectory, String fileName) {
        return StringUtils.hasText(subDirectory) ? subDirectory + "/" + fileName : fileName;
    }

    public List<ModelCodeDTO> generateAllModelCodes(String packageName, DesignModel designModel) {
        List<DesignCodeLang> codeLangs = metadataResolver.getAvailableCodeLangs();
        if (codeLangs.isEmpty()) {
            return List.of(generateModelCode(packageName, DesignCodeLang.JAVA, designModel, false));
        }
        return codeLangs.stream()
                .map(codeLang -> generateModelCode(packageName, codeLang, designModel, true))
                .toList();
    }

    /**
     * Generate model code using database templates first, then fall back to classpath templates.
     *
     * @param packageName package name
     * @param requestedCodeLang requested code language
     * @param designModel model with fields
     * @return model code object
     */
    public ModelCodeDTO generateModelCode(String packageName, DesignCodeLang requestedCodeLang, DesignModel designModel) {
        boolean databaseTemplateConfigured = !metadataResolver.getAvailableCodeLangs().isEmpty();
        DesignCodeLang codeLang = requestedCodeLang != null ? requestedCodeLang : DesignCodeLang.JAVA;
        return generateModelCode(packageName, codeLang, designModel, databaseTemplateConfigured);
    }

    private ModelCodeDTO generateModelCode(String packageName,
                                           DesignCodeLang codeLang,
                                           DesignModel designModel,
                                           boolean databaseTemplateConfigured) {
        Map<String, Object> modelData = BeanTool.objectToMap(designModel);
        modelData.put("packageName", packageName == null ? "" : packageName);
        modelData.put("codeLang", codeLang);
        modelData.putAll(getAdditionalParams());

        Map<FieldType, DesignFieldCodeMapping> codeMappings = metadataResolver.getFieldCodeMappings(codeLang);
        enrichFieldCodeTypes(modelData, codeLang, codeMappings);

        List<CodeTemplateSpec> codeTemplates = resolveCodeTemplates(codeLang, databaseTemplateConfigured);
        if (databaseTemplateConfigured && codeTemplates.isEmpty()) {
            throw new IllegalArgumentException("The code language {0} has no configured code templates!", codeLang.getCode());
        }
        if (codeTemplates.isEmpty()) {
            throw new IllegalArgumentException("No code templates were found under {0}.", TEMPLATE_DIR);
        }
        DesignCodeLang effectiveCodeLang = databaseTemplateConfigured ? codeLang : DesignCodeLang.JAVA;

        ModelCodeDTO modelCode = new ModelCodeDTO(designModel.getModelName(), packageName, effectiveCodeLang);
        modelCode.setFiles(codeTemplates.stream()
                .map(template -> renderCodeFile(template, modelData))
                .toList());
        return modelCode;
    }

    private record CodeTemplateSpec(Long templateId,
                                    String templateName,
                                    Integer sequence,
                                    String subDirectoryTemplate,
                                    String fileNameTemplate,
                                    String classpathTemplatePath,
                                    String templateContent) {}
}
