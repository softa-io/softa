package io.softa.starter.file.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.file.dto.SigningDocumentSignRequest;
import io.softa.starter.file.dto.SigningDocumentSignResponse;
import io.softa.starter.file.entity.SigningDocument;
import io.softa.starter.file.service.SigningDocumentService;

/**
 * SigningDocument Model Controller
 */
@Tag(name = "SigningDocument")
@RestController
@RequestMapping("/SigningDocument")
public class SigningDocumentController extends EntityController<SigningDocumentService, SigningDocument, Long> {

    @Operation(summary = "Sign a signing document")
    @PostMapping(value = "/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SigningDocumentSignResponse> sign(
            @RequestParam("id") Long id,
            @RequestPart("signatureFile") MultipartFile signatureFile,
            @Valid @RequestPart("payload") SigningDocumentSignRequest payload) {
        return ApiResponse.success(service.sign(id, signatureFile, payload));
    }

}
