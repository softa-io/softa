package io.softa.starter.studio.meta.controller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.studio.dto.ModelCodeDTO;
import io.softa.starter.studio.dto.ModelCodeFileDTO;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.meta.service.DesignModelService;
import io.softa.starter.studio.template.enums.DesignCodeLang;

/**
 * DesignModel Model Controller
 */
@Tag(name = "DesignModel")
@RestController
@RequestMapping("/DesignModel")
public class DesignModelController extends EntityController<DesignModelService, DesignModel, Long> {

    /**
     * Preview the DDL SQL of model, including table creation and index creation
     *
     * @param id Model ID
     * @return Model DDL SQL
     */
    @Operation(description = "Preview the DDL SQL of model, including table creation and index creation")
    @GetMapping(value = "/previewDDL")
    @Parameter(name = "id", description = "Model ID")
    public ApiResponse<String> previewDDL(@RequestParam Long id) {
        return ApiResponse.success(service.previewDDL(id));
    }

    /**
     * Preview the generated model code for the specified language.
     *
     * @param id Model ID
     * @return Generated model code files
     */
    @Operation(description = "Preview the generated model code for the specified language")
    @GetMapping(value = "/previewCode")
    @Parameters({
            @Parameter(name = "id", description = "Model ID"),
            @Parameter(name = "codeLang", description = "Code language. Optional when only one language is available.")
    })
    public ApiResponse<ModelCodeDTO> previewCode(@RequestParam Long id,
                                                 @RequestParam(required = false) String codeLang) {
        return ApiResponse.success(service.previewCode(id, DesignCodeLang.of(codeLang)));
    }

    @Operation(description = "Preview all generated model code packages grouped by language")
    @GetMapping(value = "/previewAllCode")
    @Parameter(name = "id", description = "Model ID")
    public ApiResponse<List<ModelCodeDTO>> previewAllCode(@RequestParam Long id) {
        return ApiResponse.success(service.previewAllCode(id));
    }

    /**
     * Download the specified code file.
     *
     * @param id Model ID
     * @param relativePath Generated relative path returned by previewCode
     * @return Single code file
     */
    @Operation(description = "Download specified code file")
    @GetMapping(value = "/downloadCode")
    @Parameters({
            @Parameter(name = "id", description = "Model ID"),
            @Parameter(name = "codeLang", description = "Code language. Optional when only one language is available."),
            @Parameter(name = "relativePath", description = "Generated relative path returned by previewCode.")
    })
    public ResponseEntity<byte[]> downloadCode(@RequestParam Long id,
                                               @RequestParam(required = false) String codeLang,
                                               @RequestParam String relativePath) {
        ModelCodeFileDTO file = service.previewCode(id, DesignCodeLang.of(codeLang)).getFile(relativePath);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", file.getFileName());
        return new ResponseEntity<>(file.getContent().getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
    }

    /**
     * Download the model code zip package for a single language.
     *
     * @param id Model ID
     * @return Model code zip package
     */
    @Operation(description = "Download model code package")
    @GetMapping(value = "/downloadZip")
    @Parameters({
            @Parameter(name = "id", description = "Model ID"),
            @Parameter(name = "codeLang", description = "Code language. Optional when only one language is available.")
    })
    public ResponseEntity<byte[]> downloadZip(@RequestParam Long id,
                                              @RequestParam(required = false) String codeLang) {
        ModelCodeDTO modelCodeDTO = service.previewCode(id, DesignCodeLang.of(codeLang));
        byte[] zipContent = buildZip(List.of(modelCodeDTO), false);
        String zipFileName = modelCodeDTO.getModelName() + "-" + modelCodeDTO.getCodeLang().getCode() + ".zip";
        return buildZipResponse(zipContent, zipFileName);
    }

    @Operation(description = "Download all model code packages in one zip")
    @GetMapping(value = "/downloadAllZip")
    @Parameter(name = "id", description = "Model ID")
    public ResponseEntity<byte[]> downloadAllZip(@RequestParam Long id) {
        List<ModelCodeDTO> modelCodes = service.previewAllCode(id);
        byte[] zipContent = buildZip(modelCodes, true);
        String zipFileName = modelCodes.getFirst().getModelName() + "-all.zip";
        return buildZipResponse(zipContent, zipFileName);
    }

    private byte[] buildZip(List<ModelCodeDTO> modelCodes, boolean prefixCodeLang) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            for (ModelCodeDTO modelCodeDTO : modelCodes) {
                String pathPrefix = prefixCodeLang ? modelCodeDTO.getCodeLang().getCode() + "/" : "";
                for (Map.Entry<String, String> entry : modelCodeDTO.fileCodeMap().entrySet()) {
                    zipOutputStream.putNextEntry(new ZipEntry(pathPrefix + entry.getKey()));
                    zipOutputStream.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zipOutputStream.closeEntry();
                }
            }
        } catch (IOException e) {
            String modelName = modelCodes.isEmpty() ? "" : modelCodes.getFirst().getModelName();
            throw new IllegalArgumentException("Failed to generate file package of model {0}:", modelName, e);
        }
        return outputStream.toByteArray();
    }

    private ResponseEntity<byte[]> buildZipResponse(byte[] zipContent, String zipFileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", zipFileName);
        return new ResponseEntity<>(zipContent, headers, HttpStatus.OK);
    }
}
