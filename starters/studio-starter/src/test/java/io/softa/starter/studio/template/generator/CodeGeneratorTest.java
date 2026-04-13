package io.softa.starter.studio.template.generator;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.placeholder.TemplateEngine;
import io.softa.framework.base.utils.MapUtils;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.studio.dto.ModelCodeDTO;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.template.entity.*;
import io.softa.starter.studio.template.enums.DesignCodeLang;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class CodeGeneratorTest {

    private final CodeGenerator codeGenerator = new CodeGenerator(new DesignGenerationMetadataResolver() {
        @Override
        public Map<FieldType, DesignFieldTypeDefault> getFieldTypeDefaults() {
            return Map.of();
        }

        @Override
        public Map<FieldType, DesignFieldDbMapping> getFieldDbMappings(DatabaseType databaseType) {
            return Map.of();
        }

        @Override
        public Optional<DesignSqlTemplate> getSqlTemplate(DatabaseType databaseType) {
            return Optional.empty();
        }

        @Override
        public Map<FieldType, DesignFieldCodeMapping> getFieldCodeMappings(DesignCodeLang codeLang) {
            return Map.of();
        }

        @Override
        public List<DesignCodeTemplate> getCodeTemplates(DesignCodeLang codeLang) {
            return List.of();
        }

        @Override
        public List<DesignCodeLang> getAvailableCodeLangs() {
            return List.of();
        }
    });

    private Map<String, Object> mockModel() {
        return MapUtils.strObj()
                .put("userName", "Tom")
                .put("currentDate", "2024")
                .put("packageName", "io.softa.framework.demo")
                .put("modelName", "SysModel")
                .build();
    }

    private Map<String, Object> mockField() {
        FieldType[] fieldTypes = FieldType.values();
        int randomIndex = new Random().nextInt(fieldTypes.length);
        FieldType fieldType = fieldTypes[randomIndex];
        String javaType = switch (fieldType) {
            case ONE_TO_MANY -> "List<DeptInfo>";
            case MANY_TO_MANY, MULTI_FILE -> "List<Long>";
            case MULTI_OPTION, MULTI_STRING -> "List<String>";
            case DTO -> "JsonNode";
            default -> fieldType.getJavaType() != null ? fieldType.getJavaType().getSimpleName() : "Object";
        };
        return MapUtils.strObj()
                .put("fieldType", fieldType.getType())
                .put("javaType", javaType)
                .put("labelName", "fieldType: " + fieldType.getType())
                .put("fieldName", "deptName")
                .put("relatedModel", "DeptInfo")
                .build();
    }

    private static final String TEMPLATE_DIR = "templates/code/";

    @Test
    void generateService() {
        String code = TemplateEngine.renderFilePath(TEMPLATE_DIR + "service/{{modelName}}Service.java.peb", mockModel());
        assertNotNull(code);
    }

    @Test
    void generateEntity() {
        Map<String, Object> modelData = mockModel();
        List<Map<String, Object>> modelFields = IntStream.range(0, 5).mapToObj(i -> mockField()).collect(Collectors.toList());
        modelData.put("modelFields", modelFields);
        String code = TemplateEngine.renderFilePath(TEMPLATE_DIR + "entity/{{modelName}}.java.peb", modelData);
        log.info("Generated entity code:\n{}", code);
        assertNotNull(code);
    }

    @Test
    void generateModelCodeUsesOptionSetCodeForOptionTypes() {
        DesignModel designModel = new DesignModel();
        designModel.setModelName("SysModel");
        designModel.setDescription("test");
        designModel.setModelFields(List.of(
                mockDesignField("status", "Status", FieldType.OPTION, null),
                mockDesignField("tags", "Tag", FieldType.MULTI_OPTION, null),
                mockDesignField("deptInfos", null, FieldType.ONE_TO_MANY, "DeptInfo")
        ));

        ModelCodeDTO modelCode = codeGenerator.generateModelCode("io.softa.framework.demo", DesignCodeLang.JAVA, designModel);
        String entityCode = findFileContent(modelCode, "entity/SysModel.java");

        assertTrue(entityCode.contains("private Status status;"));
        assertTrue(entityCode.contains("private List<Tag> tags;"));
        assertTrue(entityCode.contains("private List<DeptInfo> deptInfos;"));
    }

    @Test
    void generateModelCodeFallsBackWhenOptionSetCodeMissing() {
        DesignModel designModel = new DesignModel();
        designModel.setModelName("SysModel");
        designModel.setModelFields(List.of(
                mockDesignField("status", null, FieldType.OPTION, null),
                mockDesignField("tags", null, FieldType.MULTI_OPTION, null)
        ));

        ModelCodeDTO modelCode = codeGenerator.generateModelCode("io.softa.framework.demo", DesignCodeLang.JAVA, designModel);
        String entityCode = findFileContent(modelCode, "entity/SysModel.java");

        assertTrue(entityCode.contains("private String status;"));
        assertTrue(entityCode.contains("private List<String> tags;"));
    }

    @Test
    void generateAllModelCodesFallsBackToSingleJavaPackageWhenDatabaseTemplatesMissing() {
        DesignModel designModel = new DesignModel();
        designModel.setModelName("SysModel");
        designModel.setModelFields(List.of());

        List<ModelCodeDTO> modelCodes = codeGenerator.generateAllModelCodes("io.softa.framework.demo", designModel);

        assertEquals(1, modelCodes.size());
        assertEquals(DesignCodeLang.JAVA, modelCodes.getFirst().getCodeLang());
        assertEquals(
                List.of(
                        "controller/SysModelController.java",
                        "entity/SysModel.java",
                        "service/SysModelService.java",
                        "service/impl/SysModelServiceImpl.java"
                ),
                modelCodes.getFirst().fileCodeMap().keySet().stream().toList());
    }

    private DesignField mockDesignField(String fieldName, String optionSetCode, FieldType fieldType, String relatedModel) {
        DesignField field = new DesignField();
        field.setFieldName(fieldName);
        field.setLabelName(fieldName);
        field.setFieldType(fieldType);
        field.setOptionSetCode(optionSetCode);
        field.setRelatedModel(relatedModel);
        return field;
    }

    private String findFileContent(ModelCodeDTO modelCode, String relativePath) {
        return modelCode.getFile(relativePath).getContent();
    }
}
