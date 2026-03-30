package io.softa.starter.file.service;

import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.file.dto.SigningDocumentSignRequest;
import io.softa.starter.file.dto.SigningDocumentSignResponse;
import io.softa.starter.file.entity.SigningDocument;

/**
 * SigningDocument Model Service Interface
 */
public interface SigningDocumentService extends EntityService<SigningDocument, Long> {

    SigningDocumentSignResponse sign(Long id, MultipartFile signatureFile, SigningDocumentSignRequest payload);

}
