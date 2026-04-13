package io.softa.starter.file.service.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.placeholder.TemplateEngine;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.dto.UploadFileDTO;
import io.softa.framework.orm.enums.FileType;
import io.softa.framework.orm.service.FileService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.orm.utils.FileUtils;
import io.softa.framework.orm.utils.IDGenerator;
import io.softa.starter.file.dto.*;
import io.softa.starter.file.entity.DocumentTemplate;
import io.softa.starter.file.entity.SigningDocument;
import io.softa.starter.file.entity.SigningRequest;
import io.softa.starter.file.enums.SigningDocumentStatus;
import io.softa.starter.file.enums.SigningRequestStatus;
import io.softa.starter.file.pdf.PdfFileGenerator;
import io.softa.starter.file.pdf.PdfSigningHelper;
import io.softa.starter.file.service.DocumentTemplateService;
import io.softa.starter.file.service.SigningDocumentService;
import io.softa.starter.file.service.SigningRequestService;
import io.softa.starter.file.word.WordFileGenerator;

/**
 * SigningDocument Model Service Implementation
 */
@Service
public class SigningDocumentServiceImpl extends EntityServiceImpl<SigningDocument, Long> implements SigningDocumentService {

    private static final String DEFAULT_SIGNATURE_METHOD = "DRAW";
    private static final String DEFAULT_IMAGE_SCALE_MODE = "FIT";

    @Autowired
    private SigningRequestService signingRequestService;

    @Autowired
    private DocumentTemplateService documentTemplateService;

    @Autowired
    private FileService fileService;

    @Autowired
    private HttpServletRequest request;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SigningDocumentSignResponse sign(Long id, MultipartFile signatureFile, SigningDocumentSignRequest payload) {
        Assert.notNull(payload, "Signing payload cannot be empty.");
        Assert.notNull(signatureFile, "Signature file cannot be empty.");
        Assert.notBlank(signatureFile.getOriginalFilename(), "Signature file name cannot be empty.");
        Assert.isTrue(FileType.IMAGE_TYPE.contains(FileUtils.getActualFileType(signatureFile)),
                "Signature file must be an image.");

        SignatureEvidenceDto evidence = payload.evidence();
        if (evidence != null) {
            Assert.notTrue(Boolean.FALSE.equals(evidence.consentAccepted()), "Signature consent must be accepted.");
        }

        SigningDocument signingDocument = getById(id)
                .orElseThrow(() -> new BusinessException("Signing document not found: {0}", id));
        validateSigningDocument(signingDocument);
        SigningRequest signingRequest = validateSigningRequest(signingDocument.getSigningRequestId());

        byte[] signatureImageBytes = getMultipartBytes(signatureFile);
        FileInfo signatureImageFile = fileService.uploadFile(this.modelName, id, "signedImageId", signatureFile);

        OriginalPdfSource originalPdfSource = loadOriginalPdf(signingDocument);
        PdfSigningHelper.ResolvedPlacement resolvedPlacement = resolvePlacement(originalPdfSource.pdfBytes(), payload);
        NormalizedRenderOptions renderOptions = normalizeRenderOptions(payload.renderOptions());
        byte[] signedPdfBytes = PdfSigningHelper.stampSignature(
                originalPdfSource.pdfBytes(),
                signatureImageBytes,
                resolvedPlacement,
                renderOptions.flattenToPdf(),
                renderOptions.imageScaleMode()
        );
        FileInfo signedPdfFile = uploadSignedPdf(signingDocument, signedPdfBytes);

        LocalDateTime serverSignedAt = LocalDateTime.now();
        String evidenceId = IDGenerator.generateStringId();
        String actualUserAgent = resolveUserAgent(evidence);
        String clientIp = resolveClientIp();
        String originalPdfSha256 = sha256(originalPdfSource.pdfBytes());
        String signatureImageSha256 = sha256(signatureImageBytes);
        String signedPdfSha256 = sha256(signedPdfBytes);

        signingDocument.setStatus(SigningDocumentStatus.COMPLETED);
        signingDocument.setSignedImageId(signatureImageFile.getFileId());
        signingDocument.setSignedPdfId(signedPdfFile.getFileId());
        signingDocument.setSignerUserId(ContextHolder.getContext().getUserId());
        signingDocument.setSignSlotCode(StringUtils.trimToNull(payload.signSlotCode()));
        signingDocument.setEvidenceId(evidenceId);
        signingDocument.setSignatureEvidence(buildSignatureEvidence(
                payload,
                resolvedPlacement,
                renderOptions,
                signingRequest,
                signatureImageFile,
                signedPdfFile,
                serverSignedAt,
                clientIp,
                actualUserAgent,
                originalPdfSource.originalTemplateFileId(),
                originalPdfSha256,
                signatureImageSha256,
                signedPdfSha256,
                evidenceId
        ));
        signingDocument.setSignedTime(serverSignedAt);
        updateOne(signingDocument);

        updateSigningRequestStatus(signingRequest.getId());
        return new SigningDocumentSignResponse(
                signingDocument.getId(),
                SigningDocumentStatus.COMPLETED.getStatus(),
                signedPdfFile,
                signatureImageFile,
                toOffsetDateTime(serverSignedAt),
                evidenceId
        );
    }

    private void validateSigningDocument(SigningDocument signingDocument) {
        Assert.notNull(signingDocument.getSigningRequestId(), "Signing request ID is required for signing.");
        Assert.notNull(signingDocument.getTemplateId(), "Document template ID is required for signing.");
        if (SigningDocumentStatus.COMPLETED.equals(signingDocument.getStatus()) || signingDocument.getSignedPdfId() != null) {
            throw new BusinessException("Signing document `{0}` has already been signed.", signingDocument.getId());
        }
    }

    private SigningRequest validateSigningRequest(Long signingRequestId) {
        SigningRequest signingRequest = signingRequestService.getById(signingRequestId)
                .orElseThrow(() -> new BusinessException("Signing request not found: {0}", signingRequestId));
        Long currentUserId = ContextHolder.getContext().getUserId();
        Assert.notNull(currentUserId, "Current user is required for signing.");
        if (signingRequest.getRecipient() != null && !Objects.equals(signingRequest.getRecipient(), currentUserId)) {
            throw new BusinessException("You are not allowed to sign this document.");
        }
        if (signingRequest.getExpiresTime() != null && signingRequest.getExpiresTime().isBefore(LocalDateTime.now())) {
            if (!SigningRequestStatus.EXPIRED.equals(signingRequest.getStatus())) {
                signingRequest.setStatus(SigningRequestStatus.EXPIRED);
                signingRequestService.updateOne(signingRequest);
            }
            throw new BusinessException("Signing request has expired.");
        }
        SigningRequestStatus requestStatus = signingRequest.getStatus();
        if (requestStatus == SigningRequestStatus.DRAFT
                || requestStatus == SigningRequestStatus.CANCELLED
                || requestStatus == SigningRequestStatus.COMPLETED
                || requestStatus == SigningRequestStatus.EXPIRED) {
            throw new BusinessException("Signing request status `{0}` does not allow signing.", requestStatus.getStatus());
        }
        return signingRequest;
    }

    private OriginalPdfSource loadOriginalPdf(SigningDocument signingDocument) {
        DocumentTemplate template = documentTemplateService.getById(signingDocument.getTemplateId())
                .orElseThrow(() -> new BusinessException("Document template not found: {0}", signingDocument.getTemplateId()));

        if (template.getFileId() != null) {
            FileInfo templateFileInfo = fileService.getByFileId(template.getFileId())
                    .orElseThrow(() -> new BusinessException("Template file not found: {0}", template.getFileId()));
            try (InputStream inputStream = fileService.downloadStream(template.getFileId())) {
                byte[] templateFileBytes = inputStream.readAllBytes();
                byte[] originalPdfBytes = switch (templateFileInfo.getFileType()) {
                    case PDF -> templateFileBytes;
                    case DOCX -> renderWordTemplateToPdf(templateFileBytes);
                    default -> throw new BusinessException(
                            "Signing only supports PDF or DOCX template files. Current file type: {0}",
                            templateFileInfo.getFileType().getType());
                };
                return new OriginalPdfSource(template.getFileId(), originalPdfBytes);
            } catch (IOException e) {
                throw new SystemException("Failed to load the template file for signing.", e);
            }
        }
        if (StringUtils.isNotBlank(template.getHtmlTemplate())) {
            String renderedHtml = TemplateEngine.render(template.getHtmlTemplate(), Collections.emptyMap());
            return new OriginalPdfSource(null, PdfFileGenerator.convertHtmlToPdf(renderedHtml));
        }
        throw new BusinessException("The signing document template does not contain a signable source file.");
    }

    private byte[] renderWordTemplateToPdf(byte[] templateFileBytes) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(templateFileBytes);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            WordFileGenerator.renderDocument(inputStream, Collections.emptyMap(), outputStream);
            return WordFileGenerator.convertDocxToPdf(outputStream.toByteArray());
        } catch (IOException e) {
            throw new SystemException("Failed to render the signing DOCX template.", e);
        }
    }

    private PdfSigningHelper.ResolvedPlacement resolvePlacement(byte[] originalPdfBytes, SigningDocumentSignRequest payload) {
        PdfSigningHelper.ResolvedPlacement fieldPlacement = PdfSigningHelper.resolveFieldPlacement(
                originalPdfBytes,
                payload.signSlotCode()
        );
        if (fieldPlacement != null) {
            return fieldPlacement;
        }
        if (StringUtils.isNotBlank(payload.signSlotCode()) && payload.placement() == null) {
            throw new BusinessException("Sign slot `{0}` does not exist in the PDF template.", payload.signSlotCode());
        }
        return resolveManualPlacement(originalPdfBytes, payload.placement());
    }

    private PdfSigningHelper.ResolvedPlacement resolveManualPlacement(byte[] originalPdfBytes, SignaturePlacementDto placement) {
        Assert.notNull(placement, "Either signSlotCode or placement must be provided.");
        Assert.notNull(placement.page(), "Placement page is required.");
        Assert.notNull(placement.x(), "Placement x is required.");
        Assert.notNull(placement.y(), "Placement y is required.");
        Assert.notNull(placement.width(), "Placement width is required.");
        Assert.notNull(placement.height(), "Placement height is required.");

        int page = placement.page() == 0 ? 1 : placement.page();
        int pageCount = PdfSigningHelper.getPageCount(originalPdfBytes);
        Assert.isTrue(page >= 1 && page <= pageCount,
                "Placement page `{0}` is out of range. PDF page count: {1}.", page, pageCount);

        float x = toPoints(placement.x(), placement.unit());
        float y = toPoints(placement.y(), placement.unit());
        float width = toPoints(placement.width(), placement.unit());
        float height = toPoints(placement.height(), placement.unit());
        Assert.isTrue(width > 0 && height > 0, "Placement width and height must be greater than zero.");
        return new PdfSigningHelper.ResolvedPlacement(page, x, y, width, height);
    }

    private float toPoints(BigDecimal value, String unit) {
        float number = value.floatValue();
        String normalizedUnit = StringUtils.defaultIfBlank(unit, "PT").trim().toUpperCase(Locale.ROOT);
        return switch (normalizedUnit) {
            case "PT" -> number;
            case "PX" -> number * 72F / 96F;
            case "MM" -> number * 72F / 25.4F;
            case "CM" -> number * 72F / 2.54F;
            case "IN" -> number * 72F;
            default -> throw new BusinessException("Unsupported placement unit: {0}", unit);
        };
    }

    private NormalizedRenderOptions normalizeRenderOptions(SignRenderOptionsDto renderOptions) {
        boolean flattenToPdf = renderOptions == null || !Boolean.FALSE.equals(renderOptions.flattenToPdf());
        boolean keepSignatureImage = renderOptions == null || !Boolean.FALSE.equals(renderOptions.keepSignatureImage());
        String imageScaleMode = DEFAULT_IMAGE_SCALE_MODE;
        if (renderOptions != null && StringUtils.isNotBlank(renderOptions.imageScaleMode())) {
            imageScaleMode = renderOptions.imageScaleMode().trim().toUpperCase(Locale.ROOT);
        }
        return new NormalizedRenderOptions(flattenToPdf, keepSignatureImage, imageScaleMode);
    }

    private FileInfo uploadSignedPdf(SigningDocument signingDocument, byte[] signedPdfBytes) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(signedPdfBytes)) {
            UploadFileDTO uploadFileDTO = new UploadFileDTO();
            uploadFileDTO.setModelName(this.modelName);
            uploadFileDTO.setRowId(signingDocument.getId());
            uploadFileDTO.setFileName(StringUtils.defaultIfBlank(
                    signingDocument.getTitle(),
                    "signing_document_" + signingDocument.getId()) + "_signed");
            uploadFileDTO.setFileType(FileType.PDF);
            uploadFileDTO.setFileSize(Math.max(1, signedPdfBytes.length / 1024));
            uploadFileDTO.setInputStream(inputStream);
            return fileService.uploadFromStream(uploadFileDTO);
        } catch (IOException e) {
            throw new SystemException("Failed to upload the signed PDF file.", e);
        }
    }

    private void updateSigningRequestStatus(Long signingRequestId) {
        SigningRequest signingRequest = signingRequestService.getById(signingRequestId)
                .orElseThrow(() -> new BusinessException("Signing request not found: {0}", signingRequestId));
        SigningRequestStatus nextStatus;
        if (signingRequest.getExpiresTime() != null && signingRequest.getExpiresTime().isBefore(LocalDateTime.now())) {
            nextStatus = SigningRequestStatus.EXPIRED;
        } else {
            Filters filters = Filters.of("signingRequestId", Operator.EQUAL, signingRequestId);
            var signingDocuments = searchList(filters);
            boolean allCompleted = !signingDocuments.isEmpty() && signingDocuments.stream()
                    .allMatch(document -> SigningDocumentStatus.COMPLETED.equals(document.getStatus()));
            nextStatus = allCompleted ? SigningRequestStatus.COMPLETED : SigningRequestStatus.IN_PROGRESS;
        }
        if (!Objects.equals(signingRequest.getStatus(), nextStatus)) {
            signingRequest.setStatus(nextStatus);
            signingRequestService.updateOne(signingRequest);
        }
    }

    private JsonNode buildSignatureEvidence(SigningDocumentSignRequest payload,
                                            PdfSigningHelper.ResolvedPlacement resolvedPlacement,
                                            NormalizedRenderOptions renderOptions,
                                            SigningRequest signingRequest,
                                            FileInfo signatureImageFile,
                                            FileInfo signedPdfFile,
                                            LocalDateTime serverSignedAt,
                                            String clientIp,
                                            String actualUserAgent,
                                            Long originalTemplateFileId,
                                            String originalPdfSha256,
                                            String signatureImageSha256,
                                            String signedPdfSha256,
                                            String evidenceId) {
        Map<String, Object> evidencePayload = new LinkedHashMap<>();
        evidencePayload.put("evidenceId", evidenceId);
        evidencePayload.put("signSlotCode", StringUtils.trimToNull(payload.signSlotCode()));
        evidencePayload.put("clientPayload", payload);

        Map<String, Object> resolvedPlacementMap = new LinkedHashMap<>();
        resolvedPlacementMap.put("page", resolvedPlacement.page());
        resolvedPlacementMap.put("x", resolvedPlacement.x());
        resolvedPlacementMap.put("y", resolvedPlacement.y());
        resolvedPlacementMap.put("width", resolvedPlacement.width());
        resolvedPlacementMap.put("height", resolvedPlacement.height());
        resolvedPlacementMap.put("unit", "PT");
        evidencePayload.put("resolvedPlacement", resolvedPlacementMap);

        Map<String, Object> renderOptionsMap = new LinkedHashMap<>();
        renderOptionsMap.put("flattenToPdf", renderOptions.flattenToPdf());
        renderOptionsMap.put("keepSignatureImage", renderOptions.keepSignatureImage());
        renderOptionsMap.put("imageScaleMode", renderOptions.imageScaleMode());
        evidencePayload.put("resolvedRenderOptions", renderOptionsMap);

        Map<String, Object> serverEvidence = new LinkedHashMap<>();
        serverEvidence.put("signatureMethod", resolveSignatureMethod(payload.evidence()));
        serverEvidence.put("signerUserId", ContextHolder.getContext().getUserId());
        serverEvidence.put("signerName", ContextHolder.getContext().getName());
        serverEvidence.put("signingRequestId", signingRequest.getId());
        serverEvidence.put("serverSignedAt", toOffsetDateTime(serverSignedAt));
        serverEvidence.put("clientIp", clientIp);
        serverEvidence.put("userAgent", actualUserAgent);
        serverEvidence.put("signatureImageFileId", signatureImageFile.getFileId());
        serverEvidence.put("generatedSignedFileId", signedPdfFile.getFileId());
        serverEvidence.put("originalTemplateFileId", originalTemplateFileId);
        serverEvidence.put("originalPdfSha256", originalPdfSha256);
        serverEvidence.put("signatureImageSha256", signatureImageSha256);
        serverEvidence.put("signedPdfSha256", signedPdfSha256);
        evidencePayload.put("serverEvidence", serverEvidence);
        return JsonUtils.objectToJsonNode(evidencePayload);
    }

    private byte[] getMultipartBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new SystemException("Failed to read the uploaded signature file.", e);
        }
    }

    private String resolveSignatureMethod(SignatureEvidenceDto evidence) {
        if (evidence == null || StringUtils.isBlank(evidence.signatureMethod())) {
            return DEFAULT_SIGNATURE_METHOD;
        }
        return evidence.signatureMethod().trim().toUpperCase(Locale.ROOT);
    }

    private String resolveUserAgent(SignatureEvidenceDto evidence) {
        String headerUserAgent = request.getHeader("User-Agent");
        if (StringUtils.isNotBlank(headerUserAgent)) {
            return headerUserAgent;
        }
        return evidence == null ? null : StringUtils.trimToNull(evidence.userAgent());
    }

    private String resolveClientIp() {
        String clientIp = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(clientIp)) {
            return clientIp.split(",")[0].trim();
        }
        clientIp = request.getHeader("X-Real-IP");
        if (StringUtils.isNotBlank(clientIp)) {
            return clientIp.trim();
        }
        return StringUtils.trimToNull(request.getRemoteAddr());
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new SystemException("SHA-256 algorithm is unavailable.", e);
        }
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private record OriginalPdfSource(Long originalTemplateFileId, byte[] pdfBytes) {
    }

    private record NormalizedRenderOptions(boolean flattenToPdf, boolean keepSignatureImage, String imageScaleMode) {
    }
}
