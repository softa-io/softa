package io.softa.framework.orm.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.domain.FileStream;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.dto.DownloadFileDTO;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.dto.UploadFileDTO;
import io.softa.framework.orm.entity.FileRecord;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FileSource;
import io.softa.framework.orm.enums.FileType;
import io.softa.framework.orm.oss.OSSProperties;
import io.softa.framework.orm.oss.OssClientService;
import io.softa.framework.orm.service.FileService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.utils.FileUtils;
import io.softa.framework.orm.utils.HttpDownloadUtils;
import io.softa.framework.orm.utils.IDGenerator;

/**
 * FileRecord Service Implementation
 */
@Service
@Slf4j
public class FileServiceImpl extends EntityServiceImpl<FileRecord, Long> implements FileService {

    @Autowired
    private OssClientService ossClientService;

    @Autowired
    private OSSProperties ossProperties;

    @Autowired
    private PermissionService permissionService;

    /**
     * Generate an OSS key for the file
     * ModelName is used as the prefix of the OSS key, to store files in different directories
     * Set the UUID as a part of the OSS key to avoid conflicts between files with the same name
     *
     * @param modelName the name of the corresponding business model
     * @param fileName the name of the file
     * @return the generated OSS key
     */
    public String generateOssKey(String modelName, String fileName) {
        StringBuilder key = new StringBuilder();
        // Set the subdirectory
        if (StringUtils.isNotBlank(ossProperties.getSubDir())) {
            key.append(ossProperties.getSubDir()).append("/");
        }
        // Add tenantId as a subdirectory if multi-tenancy is enabled
        if (SystemConfig.env.isEnableMultiTenancy()) {
            Long tenantId = ContextHolder.getContext().getTenantId();
            if (tenantId != null) {
                key.append(tenantId).append("/");
            }
        }
        // Set the model name as a subdirectory if it is not null
        if (StringUtils.isNotBlank(modelName)) {
            key.append(modelName).append("/");
        } else {
            key.append(FileConstant.DEFAULT_SUBFOLDER).append("/");
        }
        // Set the UUID as a part of the OSS key
        key.append(IDGenerator.generateStringId()).append("/").append(fileName);
        return key.toString();
    }

    /**
     * Generate a full filename combining the filename, the current date and the file type extension.
     *
     * @param fileName the name of the file
     * @param fileType the type of the file
     * @return the full file name
     */
    private static String getFullFileName(String fileName, FileType fileType) {
        return fileName + "_" + DateUtils.getCurrentSimpleDateString() + fileType.getExtension();
    }

    /**
     * Upload a file to the OSS and create a corresponding FileRecord.
     * The uploadFileDTO contains the file information and input stream.
     *
     * @param uploadFileDTO the upload file DTO
     * @return the fileRecord object
     */
    private FileRecord uploadFileWithDTO(UploadFileDTO uploadFileDTO) {
        String fileName = uploadFileDTO.getFileName();
        FileType fileType = uploadFileDTO.getFileType();
        String fullFileName = getFullFileName(fileName, fileType);
        String ossKey = this.generateOssKey(uploadFileDTO.getModelName(), fullFileName);
        String checksum = ossClientService.uploadStreamToOSS(ossKey, uploadFileDTO.getInputStream(), fileName);
        // Create file record
        FileRecord fileRecord = new FileRecord();
        fileRecord.setFileName(fullFileName);
        fileRecord.setFileType(uploadFileDTO.getFileType());
        fileRecord.setOssKey(ossKey);
        fileRecord.setSource(uploadFileDTO.getFileSource());
        fileRecord.setChecksum(checksum);
        fileRecord.setFileSize(uploadFileDTO.getFileSize());
        fileRecord.setModelName(uploadFileDTO.getModelName());
        fileRecord.setRowId(uploadFileDTO.getRowId() == null ? null : uploadFileDTO.getRowId().toString());
        Long id = this.persistFileRecord(fileRecord);
        fileRecord.setId(id);
        return fileRecord;
    }

    /**
     * Upload a file to the OSS and return the fileInfo object with download URL
     * The uploadFileDTO contains the file information and input stream.
     *
     * @param uploadFileDTO the upload file DTO
     * @return a FileInfo object containing the download URL and metadata of the uploaded file
     */
    @Override
    public FileInfo uploadFromStream(UploadFileDTO uploadFileDTO) {
        uploadFileDTO.setFileSource(FileSource.DOWNLOAD);
        FileRecord fileRecord = this.uploadFileWithDTO(uploadFileDTO);
        return convertToFileInfo(fileRecord, FileConstant.DEFAULT_DOWNLOAD_URL_EXPIRE, true);
    }

    /**
     * Upload a file to the OSS and create a corresponding FileRecord to associate with a business model and rowId.
     *
     * @param modelName the name of the corresponding business model
     * @param rowId the ID of the corresponding business row data
     * @param fieldName The field name of the file belongs to
     * @param file the file to be uploaded
     * @return fileRecord object
     */
    private FileRecord uploadFileToField(String modelName, Serializable rowId, String fieldName, MultipartFile file) {
        String fileName = FileUtils.getShortFileName(file);
        FileType fileType = FileUtils.getActualFileType(file);
        String fullFileName = getFullFileName(fileName, fileType);
        String ossKey = this.generateOssKey(modelName, fullFileName);
        String checksum;
        try (InputStream inputStream = file.getInputStream()) {
            checksum = ossClientService.uploadStreamToOSS(ossKey, inputStream, fileName);
        } catch (IOException e) {
            throw new SystemException("Failed to upload file {0}.", fileName + fileType.getExtension() , e);
        }
        // Create file record
        FileRecord fileRecord = new FileRecord();
        fileRecord.setModelName(modelName);
        fileRecord.setRowId(rowId == null ? null : rowId.toString());
        fileRecord.setFieldName(fieldName);
        // Set to the original name of the uploaded file
        fileRecord.setFileName(file.getOriginalFilename());
        fileRecord.setFileType(fileType);
        fileRecord.setOssKey(ossKey);
        fileRecord.setSource(FileSource.UPLOAD);
        fileRecord.setChecksum(checksum);
        // bytes to KB
        fileRecord.setFileSize((int) file.getSize() / 1024);
        Long id = this.persistFileRecord(fileRecord);
        fileRecord.setId(id);
        return fileRecord;
    }

    /**
     * Upload a file to the OSS and create a FileRecord.
     *
     * @param modelName the name of the corresponding business model
     * @param file the file to be uploaded
     * @return fileId
     */
    @Override
    public Long uploadFile(String modelName, MultipartFile file) {
        FileRecord fileRecord = this.uploadFileToField(modelName, null, null, file);
        return fileRecord.getId();
    }

    /**
     * Upload a file to the OSS and create a corresponding FileRecord to associate
     * with a business model and rowId.
     *
     * @param modelName the name of the corresponding business model
     * @param rowId     the ID of the corresponding business row data
     * @param fieldName The field name of the file belongs to
     * @param file      the file to be uploaded
     * @return fileInfo object
     */
    @Override
    public FileInfo uploadFile(String modelName, Serializable rowId, String fieldName, MultipartFile file) {
        FileRecord fileRecord = this.uploadFileToField(modelName, rowId, fieldName, file);
        return this.convertToFileInfo(fileRecord);
    }

    /**
     * Upload a file from URL to the OSS and create a corresponding FileRecord to associate
     * with a business model and rowId.
     *
     * @param modelName the name of the corresponding business model
     * @param rowId     the ID of the corresponding business row data
     * @param fieldName The field name of the file belongs to
     * @param url       the URL of the file
     * @param expireSeconds the expiration time in seconds
     * @return fileInfo object
     */
    @Override
    public FileInfo uploadFromUrl(String modelName, Serializable rowId, String fieldName, String url,
            int expireSeconds) {
        DownloadFileDTO downloadResult = null;
        try {
            downloadResult = HttpDownloadUtils.downloadFromUrl(url);
            FileStream fileStream = downloadResult.getFileStream();
            String originalFileName = fileStream.getFileName();
            String ossKey = this.generateOssKey(modelName, originalFileName);
            // Upload to OSS
            String checksum;
            try (InputStream inputStream = fileStream.getInputStream()) {
                checksum = ossClientService.uploadStreamToOSS(ossKey, inputStream, originalFileName);
            } catch (IOException e) {
                throw new SystemException("Failed to upload file {0} from URL {1}.", originalFileName, url, e);
            }
            // Create file record
            FileRecord fileRecord = new FileRecord();
            fileRecord.setModelName(modelName);
            fileRecord.setRowId(rowId == null ? null : rowId.toString());
            fileRecord.setFieldName(fieldName);
            fileRecord.setFileName(originalFileName);
            fileRecord.setFileType(fileStream.getFileType());
            fileRecord.setOssKey(ossKey);
            fileRecord.setSource(FileSource.URL);
            fileRecord.setChecksum(checksum);
            fileRecord.setFileSize(fileStream.getFileSize());

            Long id = this.persistFileRecord(fileRecord);
            fileRecord.setId(id);

            return this.convertToFileInfo(fileRecord, expireSeconds, false);

        } finally {
            if (downloadResult != null) {
                downloadResult.close();
            }
        }
    }

    /**
     * Convert fileRecord object to fileInfo object
     *
     * @param fileRecord fileRecord object
     * @return fileInfo object
     */
    private FileInfo convertToFileInfo(FileRecord fileRecord) {
        return this.convertToFileInfo(fileRecord, FileConstant.DEFAULT_DOWNLOAD_URL_EXPIRE, false);
    }

    /**
     * Convert fileRecord object to fileInfo object
     *
     * @param fileRecord fileRecord object
     * @param expireSeconds the expiration time in seconds
     * @param download whether to generate a download URL or a pre-signed URL
     * @return fileInfo object
     */
    private FileInfo convertToFileInfo(FileRecord fileRecord, int expireSeconds, boolean download) {
        if (fileRecord == null) {
            return null;
        }
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileId(fileRecord.getId());
        fileInfo.setFileName(fileRecord.getFileName());
        fileInfo.setFileType(fileRecord.getFileType());
        String ossUrl;
        if (download) {
            ossUrl = ossClientService.getDownloadUrl(fileRecord.getOssKey(), expireSeconds, fileRecord.getFileName());
        } else {
            ossUrl = ossClientService.getPreSignedUrl(fileRecord.getOssKey(), expireSeconds, fileRecord.getFileName());
        }
        fileInfo.setUrl(ossUrl);
        fileInfo.setSize(fileRecord.getFileSize());
        fileInfo.setChecksum(fileRecord.getChecksum());
        return fileInfo;
    }

    /**
     * Download the file stream from the OSS bucket by fileId
     *
     * @param fileId the ID of the file to be downloaded
     * @return the InputStream of the file
     */
    @Override
    public InputStream downloadStream(Long fileId) {
        // Bypass for the same reason as the other FileRecord reads: this serves export-by-file-template
        // and document generation, both reached by ordinary users, and FileRecord's matchNone would deny
        // every one of them. The caller resolved this id from a template or document row it had already
        // read, so the entitlement was established there; tenant isolation still applies inside.
        FileRecord fileRecord = bypassFileRecordScope(() -> this.getById(fileId))
                .orElseThrow(() -> new IllegalArgumentException("FileRecord not found by fileId {0}", fileId));
        return ossClientService.downloadStreamFromOSS(fileRecord.getOssKey(), fileRecord.getFileName());
    }

    /**
     * Get the FileInfo object by fileId
     *
     * @param fileId the ID of the file
     * @return Optional object containing the FileInfo object if found, or empty if not found
     */
    @Override
    public Optional<FileInfo> getByFileId(Long fileId) {
        // Read past FileRecord's own (anchorless) scope — the caller is either the controller, which
        // has already authorized the owning row, or an internal service that resolved this id from a
        // record it was entitled to. Tenant isolation still applies inside the bypass.
        Optional<FileRecord> fileRecordOpt = bypassFileRecordScope(() -> this.getById(fileId));
        return fileRecordOpt.map(this::convertToFileInfo);
    }

    /**
     * Get the FileInfo object by fileId
     *
     * @param fileId the ID of the file
     * @param expireSeconds the expiration time in seconds
     * @return Optional object containing the FileInfo object if found, or empty if not found
     */
    @Override
    public Optional<FileInfo> getByFileId(Long fileId, int expireSeconds) {
        Optional<FileRecord> fileRecordOpt = bypassFileRecordScope(() -> this.getById(fileId));
        return fileRecordOpt.map(record -> this.convertToFileInfo(record, expireSeconds, false));
    }

    /**
     * Get the FileInfo object list by fileIds.
     *
     * @param fileIds the file IDs
     * @return the list of FileInfo objects
     */
    @Override
    public List<FileInfo> getByFileIds(List<Long> fileIds) {
        // Same bypass as the singular read, and the higher-traffic one: this is what expands a FILE /
        // MULTI_FILE column into a FileInfo on every row read. Left scoped, FileRecord's matchNone made
        // getByIds come back short and raise — so a non-admin could not open any record carrying an
        // attachment, which reads as the record being forbidden rather than the file being unreachable.
        //
        // The ids arriving here are already authorized twice over: the row read applied its own scope,
        // and a column behind a sensitive field set was dropped from the SELECT before this (Layer C
        // PRE), so a blocked field's id never reaches this call.
        List<FileRecord> fileRecords = bypassFileRecordScope(() -> this.getByIds(fileIds));
        return fileRecords.stream().map(this::convertToFileInfo).toList();
    }

    /**
     * Get the FileInfo object by modelName and rowId
     *
     * @param modelName the name of the corresponding business model
     * @param rowId the ID of the corresponding business row data
     * @return fileInfo object with download URL
     */
    @Override
    public List<FileInfo> getRowFiles(String modelName, Serializable rowId) {
        // formatId is not optional: rowId arrives bound from a query string — a String — while the
        // model's key is typically Long. Unconverted, the id check counts nothing and denies every
        // caller, which reads as a permission problem and is really a type one.
        // The business row is the thing being authorized, so the check is on it — READ access to the
        // owning row, not to FileRecord. That is the whole authorization for this call.
        permissionService.checkIdAccess(modelName, IdUtils.formatId(modelName, rowId), AccessType.READ);
        Filters filters = new Filters()
                .eq(FileRecord::getModelName, modelName)
                .eq(FileRecord::getRowId, rowId.toString());
        // The listing itself runs past FileRecord's own scope — otherwise it would come back empty for
        // every non-admin even though the owning row was just authorized above. Tenant isolation stays.
        List<FileRecord> fileRecords = bypassFileRecordScope(() -> this.searchList(filters));
        // Row access is not field access. A file hanging on a field the caller may not read — a bank
        // account attachment behind a sensitive field set — would otherwise come back in full through
        // this listing, handing back the document whose field value was masked two layers up. Files
        // recorded against no field are kept: they belong to the row itself, not to a masked column.
        return readableFiles(modelName, fileRecords).stream().map(this::convertToFileInfo).toList();
    }

    /**
     * The subset of a row's files the caller may actually see.
     *
     * <p>Row access is not field access: a file hanging on a field behind a sensitive field set is the
     * document whose column was masked two layers up, and listing it here would hand back what the mask
     * withheld. Files recorded against no field are kept — they belong to the row itself, so no field
     * mask speaks for them.
     */
    List<FileRecord> readableFiles(String modelName, List<FileRecord> fileRecords) {
        // Asks the single-file rule per record rather than restating it. The two used to be written
        // out separately and a blank-field guard went missing from one of them, which an immutable
        // blocked-set turns into a NullPointerException rather than a wrong answer.
        return fileRecords.stream()
                .filter(record -> isFileFieldReadable(modelName, record.getFieldName()))
                .toList();
    }

    /**
     * Whether a file filed against this field is readable, given what the caller may read of the model.
     *
     * <p>The listing and the by-id lookup both have to answer this, and they must answer it the same
     * way or one endpoint hands over what the other withheld. A file recorded against no field belongs
     * to the row itself, and no field mask speaks for it.
     */
    @Override
    public boolean isFileFieldReadable(String modelName, String fieldName) {
        if (StringUtils.isBlank(fieldName)) {
            return true;
        }
        return !permissionService.getUserBlockedModelFields(modelName, AccessType.READ).contains(fieldName);
    }

    /**
     * Bind uploaded files to the row that now references them.
     *
     * <p>One read and one write for the whole batch, however many rows were just written: a bulk
     * create of a hundred rows each carrying an attachment must not become a hundred round trips.
     *
     * <p>Claims naming an id that no longer exists are dropped rather than raised. The caller is a
     * business write that has already succeeded; a file deleted between upload and save is not a
     * reason to fail it, and the row simply ends up referencing nothing — which the read side
     * already tolerates.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claimFiles(Collection<FileClaim> claims, Collection<FileSlot> slots) {
        releaseVacatedSlots(claims, slots);
        if (CollectionUtils.isEmpty(claims)) {
            return;
        }
        Map<Long, FileClaim> claimById = new LinkedHashMap<>();
        for (FileClaim claim : claims) {
            if (claim != null && claim.fileId() != null) {
                claimById.put(claim.fileId(), claim);
            }
        }
        if (claimById.isEmpty()) {
            return;
        }
        // Read and write past FileRecord's own (anchorless) scope, for the same reason the upload does:
        // this runs inside a business write the caller was already authorized for, and FileRecord's
        // matchNone would otherwise throw here — leaving the attachment bound to nothing, or rather
        // failing the whole save. Tenant isolation is untouched, so a cross-tenant id still finds no row.
        List<FileRecord> records = bypassFileRecordScope(() -> this.getByIds(new ArrayList<>(claimById.keySet())));
        List<FileRecord> toUpdate = new ArrayList<>(records.size());
        for (FileRecord record : records) {
            FileClaim claim = claimById.get(record.getId());
            if (claim == null || isAlreadyClaimed(record, claim) || isOwnedByAnotherRow(record, claim)) {
                continue;
            }
            record.setModelName(claim.modelName());
            record.setRowId(claim.rowId());
            record.setFieldName(claim.fieldName());
            toUpdate.add(record);
        }
        if (!toUpdate.isEmpty()) {
            bypassFileRecordScope(() -> this.updateList(toUpdate));
        }
    }

    /**
     * Release the files a write stopped referencing.
     *
     * <p>Scoped to the (model, row, field) triples the write actually carried: within one of those, a
     * record still bound to it whose id is not among the new claims is no longer referenced, so its
     * binding is cleared. Without this, clearing an attachment left the record pointing at the row —
     * {@code getRowFiles} kept listing it and everyone who could read the row could still read it,
     * which is the file surviving its own removal.
     *
     * <p>The binding is cleared, not the file: the record goes back to unclaimed rather than being
     * deleted, so the blob is still there for whoever uploaded it and nothing is destroyed by a save.
     * Cleaning up genuinely orphaned files is a retention question, not this one.
     *
     * <p>One query per write that carried file fields, bounded by the rows written.
     */
    private void releaseVacatedSlots(Collection<FileClaim> claims, Collection<FileSlot> slots) {
        if (CollectionUtils.isEmpty(slots)) {
            return;
        }
        Set<Long> stillClaimed = claims == null ? Set.of()
                : claims.stream().map(FileClaim::fileId).filter(Objects::nonNull).collect(Collectors.toSet());
        Filters filters = null;
        for (FileSlot slot : slots) {
            Filters one = new Filters()
                    .eq(FileRecord::getModelName, slot.modelName())
                    .eq(FileRecord::getRowId, slot.rowId())
                    .eq(FileRecord::getFieldName, slot.fieldName());
            filters = filters == null ? one : Filters.or(filters, one);
        }
        Filters slotFilters = filters;
        List<FileRecord> bound = bypassFileRecordScope(() -> this.searchList(slotFilters));
        List<FileRecord> released = new ArrayList<>();
        for (FileRecord record : bound) {
            if (stillClaimed.contains(record.getId())) {
                continue;
            }
            // The binding goes; the model it was uploaded against stays. Clearing that too would make
            // the file claimable by a row of any model, so removing an attachment would widen its
            // exposure rather than return it to neutral — the opposite of what a removal means. What
            // makes it unclaimed is the absent row.
            record.setRowId(null);
            record.setFieldName(null);
            released.add(record);
        }
        if (!released.isEmpty()) {
            bypassFileRecordScope(() -> this.updateList(released));
        }
    }

    /**
     * Run a FileRecord read or write with FileRecord's own row-scope waived — and only its own.
     *
     * <p>FileRecord is anchorless: no scope rule can name it and no business model references it, so its
     * row-scope collapses to {@code matchNone()} for any non-admin. That is meaningless for a table
     * whose access is meant to derive from the row each file hangs on, and left in place it does not
     * fail safe — it fails <em>shut</em>: {@code getById} on a FileRecord throws for every non-admin
     * (the id-count comes back short), so an employee cannot read their own attachment and HR cannot
     * read the employee's. The row that actually governs the file is authorized separately —
     * {@code assertCanRead} / {@code checkIdAccess} on the owning business row — so waiving FileRecord's
     * own scope removes an obstacle, not a control.
     *
     * <p>This waives <b>only</b> permission scope. Tenant isolation is a separate flag
     * ({@code WhereBuilder.handleMultiTenant} does not consult it), so a cross-tenant file still
     * resolves to nothing here — the reason multiTenant on FileRecord is what closes the cross-tenant
     * read, not this.
     */
    private <T> T bypassFileRecordScope(java.util.function.Supplier<T> action) {
        // getContext() never returns null — an unbound thread gets a throwaway Context, and with no
        // context shouldBypass() already passes anyway, so there is nothing to branch on.
        Context context = ContextHolder.getContext();
        boolean previous = context.isSkipPermissionCheck();
        try {
            context.setSkipPermissionCheck(true);
            return action.get();
        } finally {
            context.setSkipPermissionCheck(previous);
        }
    }

    /**
     * Write the FileRecord itself, without asking whether the caller may create FileRecords.
     *
     * <p>Nobody grants that permission, and nobody should have to: a file record is bookkeeping for an
     * action the caller has already been authorized to perform — uploading an attachment to a row they
     * may edit, downloading an import template, exporting a list. FileRecord carries no anchor of its
     * own, so an ordinary user with no rule for it fails the row-scope check on insert and every
     * file-producing feature dies at the last step. Administrators skip that check, which is why this
     * only ever showed up for ordinary users — and why it reads as "Excel generation failed" rather
     * than as a permission problem.
     *
     * <p>Every value on the record is server-derived (oss key, checksum, size, the model and row it was
     * uploaded against), so there is no caller-supplied filter or scope here for the skip to widen.
     * It also does <b>not</b> waive tenant isolation — that is a separate flag — so the tenant stamp on
     * a multi-tenant FileRecord still applies.
     *
     * <p>Set directly rather than through {@code @SkipPermissionCheck}: two of the three callers are
     * private, and a self-invocation never reaches the aspect.
     */
    private Long persistFileRecord(FileRecord fileRecord) {
        return bypassFileRecordScope(() -> this.createOne(fileRecord));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<Long> copyFileTo(Long fileId, String modelName, Serializable rowId, String fieldName) {
        if (fileId == null) {
            return Optional.empty();
        }
        // Read past FileRecord's own scope for the same reason every other read here does: the caller
        // authorized the two business rows, and FileRecord's matchNone would refuse before we get to
        // copy anything. Tenant isolation still applies, so a cross-tenant source resolves to empty.
        return bypassFileRecordScope(() -> this.getById(fileId)).map(source -> {
            FileRecord copy = new FileRecord();
            // The stored object is shared — same ossKey, same checksum. Only the ownership differs.
            copy.setOssKey(source.getOssKey());
            copy.setFileName(source.getFileName());
            copy.setFileType(source.getFileType());
            copy.setChecksum(source.getChecksum());
            copy.setFileSize(source.getFileSize());
            copy.setModelName(modelName);
            copy.setRowId(rowId == null ? null : rowId.toString());
            copy.setFieldName(fieldName);
            return persistFileRecord(copy);
        });
    }

    @Override
    public Optional<FileOwner> getFileOwner(Long fileId) {
        if (fileId == null) {
            return Optional.empty();
        }
        // Bypass FileRecord's own scope: this call exists precisely to find the owning row the READ
        // should be authorized against, so gating it on FileRecord's (matchNone) scope would throw
        // before that row is ever known. Tenant isolation still applies, so a cross-tenant file
        // resolves to empty here.
        return bypassFileRecordScope(() -> this.getById(fileId)).map(record ->
                new FileOwner(record.getModelName(), record.getRowId(), record.getFieldName(),
                        record.getCreatedId()));
    }

    /**
     * True when this file already belongs to some other row — a claim must never move it.
     *
     * <p>Without this, writing someone else's file id into a row you may edit would re-point the file at
     * your row, and reading your row would then hand you their file: a path that never touches the file
     * endpoints and so is not covered by any check on them. Refusing to move a claimed file closes it —
     * the theft needs the file to change hands, and it cannot.
     *
     * <p>Left deliberately permissive for a file no row has claimed yet: uploader and saver are not
     * always the same person (a candidate uploads during pre-boarding, HR saves the record afterwards),
     * and an unclaimed file is not yet anyone's business data. Its exposure before the claim is bounded
     * separately, by {@code getByFileId} authorizing an unclaimed file against its uploader.
     */
    private boolean isOwnedByAnotherRow(FileRecord record, FileClaim claim) {
        if (StringUtils.isBlank(record.getRowId())) {
            // Unclaimed, but not therefore free: an upload always records the model it was made
            // against, even from a create form where no row exists yet. Honouring that keeps a file
            // someone is about to save onto an Employee from being pulled into a row of some other
            // model — a claim writes an id into a field the claimer may edit, and nothing else about
            // that write says whose file it was. Same-model claiming stays open on purpose: uploader
            // and saver are not always the same person (a candidate uploads during pre-boarding, HR
            // saves the record), and that case cannot be told apart from theft by identity alone.
            return StringUtils.isNotBlank(record.getModelName())
                    && !Objects.equals(record.getModelName(), claim.modelName());
        }
        return !(Objects.equals(record.getModelName(), claim.modelName())
                && Objects.equals(record.getRowId(), claim.rowId()));
    }

    /** True when the record already carries exactly this binding — re-saving a row must not churn writes. */
    private boolean isAlreadyClaimed(FileRecord record, FileClaim claim) {
        return Objects.equals(record.getModelName(), claim.modelName())
                && Objects.equals(record.getRowId(), claim.rowId())
                && Objects.equals(record.getFieldName(), claim.fieldName());
    }
}