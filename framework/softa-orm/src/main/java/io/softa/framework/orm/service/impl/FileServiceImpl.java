package io.softa.framework.orm.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.domain.FileStream;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.dto.DownloadFileDTO;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.dto.UploadFileDTO;
import io.softa.framework.orm.entity.FileRecord;
import io.softa.framework.orm.enums.FileSource;
import io.softa.framework.orm.enums.FileType;
import io.softa.framework.orm.oss.OSSProperties;
import io.softa.framework.orm.oss.OssClientService;
import io.softa.framework.orm.service.FileService;
import io.softa.framework.orm.utils.FileUtils;
import io.softa.framework.orm.utils.HttpDownloadUtils;
import io.softa.framework.orm.utils.IDGenerator;

/**
 * FileRecord Service Implementation
 */
@Service
@Slf4j
public class FileServiceImpl extends EntityServiceImpl<FileRecord, Long> implements FileService {

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    private OssClientService ossClientService;

    @Autowired
    private OSSProperties ossProperties;

    /**
     * Generate an OSS key for the file: {@code [subDir/][tenantId/]modelName/<uuid>.<ext>}.
     *
     * <p>The file's own name is not part of the key, only its extension. Embedded, it made the key as
     * long as whatever the user's file was called, and a scanned document's name easily pushed it past
     * {@code FileRecord.ossKey}: the upload failed, and the message quoted the tenant id and the storage
     * path. The name lives on {@code FileRecord.fileName}, and download and preview URLs hand it to the
     * browser in {@code Content-Disposition}, so a downloaded file is still saved under it.
     *
     * @param modelName the name of the corresponding business model
     * @param fileName the name of the file, read for its extension only
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
        // A generated name, so files with the same name do not collide
        key.append(IDGenerator.generateStringId()).append(extensionOf(fileName));
        return key.toString();
    }

    /**
     * The extension of {@code fileName}, dot included, or empty when it has none. Only a short run of
     * letters and digits counts: anything else after the last dot is part of the name, not a type.
     */
    static String extensionOf(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String extension = fileName.substring(dot + 1);
        return extension.length() <= 10 && StringUtils.isAlphanumeric(extension) ? "." + extension : "";
    }

    /** The width of {@code FileRecord.fileName}. */
    static final int MAX_FILE_NAME_LENGTH = 128;

    /** The upload-date suffix {@link #getFullFileName} puts before the extension. */
    private static final Pattern DATE_SUFFIX = Pattern.compile("_\\d{8}$");

    /**
     * {@code fileName} cut to {@link #MAX_FILE_NAME_LENGTH}, keeping its extension and upload-date
     * suffix, so a record always fits its column.
     *
     * <p>For a name the system made up — an export, a generated or signed document, an archived mail,
     * a fetched photo. Nobody chose those names and nobody can shorten them, so refusing one would
     * only fail the work it belongs to; a name a person picked is refused at upload instead.
     */
    static String fitFileName(String fileName) {
        if (fileName == null || fileName.length() <= MAX_FILE_NAME_LENGTH) {
            return fileName;
        }
        String extension = extensionOf(fileName);
        String stem = fileName.substring(0, fileName.length() - extension.length());
        Matcher date = DATE_SUFFIX.matcher(stem);
        String kept = extension;
        if (date.find()) {
            kept = date.group() + extension;
            stem = stem.substring(0, date.start());
        }
        int room = MAX_FILE_NAME_LENGTH - kept.length();
        // Never end on half a surrogate pair: an emoji cut in two is not a character.
        if (room > 0 && Character.isHighSurrogate(stem.charAt(room - 1))) {
            room--;
        }
        return stem.substring(0, Math.max(0, room)) + kept;
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
        // A name the person picked is theirs to shorten, so it is refused, before anything is stored,
        // rather than cut — and the message names no value, tenant or storage path.
        String originalName = file.getOriginalFilename();
        if (originalName != null && originalName.length() > MAX_FILE_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "File name is too long (max {0} characters). Rename the file and upload it again.",
                    MAX_FILE_NAME_LENGTH);
        }
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
        // The expansion path: this is what turns a File / MultiFile column into a FileInfo on every
        // row read, and it is where the design's rule lands — access to a file derives from access to
        // the row holding it, so the row read that got us here IS the authorization. FileRecord's own
        // scope is meaningless on top of that (see bypassFileRecordScope) and, left in place, denies
        // every non-admin: the id-count comes back short and getByIds raises, so a record carrying an
        // attachment cannot be opened at all.
        //
        // Trusting the column is only sound because assertClaimable refused to let a foreign id into
        // it in the first place. That check and this bypass are one mechanism split across write and
        // read; neither is safe without the other.
        List<FileRecord> fileRecords = bypassFileRecordScope(() -> this.getByIds(fileIds));
        return fileRecords.stream().map(this::convertToFileInfo).toList();
    }

    // ─────────────────────── ownership: written once, trusted everywhere ───────────────────────

    @Override
    public Map<Long, FileRecord> assertClaimable(String modelName, Collection<OwnershipStatement> statements) {
        if (CollectionUtils.isEmpty(statements)) {
            return Map.of();
        }
        // One read for the whole write, whatever its row count — the per-statement checks below are
        // pure in-memory comparisons against it.
        List<Long> ids = statements.stream()
                .flatMap(statement -> statement.fileIds().stream())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, FileRecord> byId = bypassFileRecordScope(() -> this.getByIds(ids)).stream()
                .collect(Collectors.toMap(FileRecord::getId, r -> r, (a, b) -> a));
        for (OwnershipStatement statement : statements) {
            for (Long fileId : statement.fileIds()) {
                if (fileId == null) {
                    continue;
                }
                FileRecord record = byId.get(fileId);
                if (record == null) {
                    throw new PermissionException("File {0} does not exist.", fileId);
                }
                if (!isClaimableBy(record, modelName, statement.rowId())) {
                    // Deliberately says nothing about whose it is: the caller supplied this id, and
                    // confirming what it belongs to would answer a question they were not entitled to ask.
                    throw new PermissionException("File {0} is not yours to attach.", fileId);
                }
            }
        }
        return byId;
    }

    /**
     * Whether this record may be pointed at by {@code (modelName, rowId)}.
     *
     * <p>Unclaimed is not the same as free. An upload always records the model it was made against,
     * even from a create form where no row exists yet, so honouring that keeps a file destined for an
     * Employee from being pulled into a row of some other model — a claim writes an id into a field
     * the claimer may edit, and nothing else about that write says whose file it was.
     */
    private boolean isClaimableBy(FileRecord record, String modelName, String rowId) {
        if (StringUtils.isBlank(record.getRowId())) {
            return sameTenantAsCaller(record)
                    && (StringUtils.isBlank(record.getModelName())
                            || Objects.equals(record.getModelName(), modelName));
        }
        return Objects.equals(record.getModelName(), modelName)
                && Objects.equals(record.getRowId(), rowId);
    }

    /**
     * An unclaimed file may only be claimed within the tenant that uploaded it — between upload and
     * save it is bound to no row, and same-model alone would let a leaked id be pulled into another
     * tenant's row. Compared by tenant, not by user: the pre-boarding flow has a candidate upload what
     * HR later saves. Enforced only when both sides are known — records from before the stamp (and
     * system writes outside any tenant) carry null and stay claimable, forward-only. The bound branch
     * above needs no tenant check: row ids are globally unique, so (model, rowId) cannot collide
     * across tenants.
     */
    private boolean sameTenantAsCaller(FileRecord record) {
        Long uploadedTenant = record.getTenantId();
        Long callerTenant = ContextHolder.getContext().getTenantId();
        if (uploadedTenant == null || callerTenant == null) {
            return true;
        }
        return Objects.equals(uploadedTenant, callerTenant);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claimFiles(Collection<FileClaim> claims, Collection<FileSlot> slots,
                           Map<Long, FileRecord> preloaded) {
        releaseVacatedSlots(claims, slots);
        Map<Long, FileClaim> claimById = new LinkedHashMap<>();
        if (claims != null) {
            for (FileClaim claim : claims) {
                if (claim != null && claim.fileId() != null) {
                    claimById.put(claim.fileId(), claim);
                }
            }
        }
        if (claimById.isEmpty()) {
            return;
        }
        List<FileRecord> records = resolveClaimRecords(claimById.keySet(), preloaded);
        List<FileRecord> toUpdate = new ArrayList<>(records.size());
        for (FileRecord record : records) {
            FileClaim claim = claimById.get(record.getId());
            if (claim == null || isBoundTo(record, claim)) {
                // Re-saving a row must not churn writes.
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
     * The records to bind, taken from what validate already read for this very write. Same thread,
     * same transaction, and nothing between the two calls touches file_record — a re-read would fetch
     * the identical rows. Ids the preload does not cover (a value filled in after validate ran, e.g. a
     * metadata default) are read individually; none exist on the standard path.
     */
    private List<FileRecord> resolveClaimRecords(Collection<Long> ids, Map<Long, FileRecord> preloaded) {
        List<FileRecord> records = new ArrayList<>(ids.size());
        List<Long> missing = new ArrayList<>();
        for (Long id : ids) {
            FileRecord preloadedRecord = preloaded == null ? null : preloaded.get(id);
            if (preloadedRecord != null) {
                records.add(preloadedRecord);
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            records.addAll(bypassFileRecordScope(() -> this.getByIds(missing)));
        }
        return records;
    }

    /** True when the record already carries exactly this binding. */
    private boolean isBoundTo(FileRecord record, FileClaim claim) {
        return Objects.equals(record.getModelName(), claim.modelName())
                && Objects.equals(record.getRowId(), claim.rowId())
                && Objects.equals(record.getFieldName(), claim.fieldName());
    }

    /**
     * Clear the binding of every file these slots no longer reference.
     *
     * <p>Scoped to the (model, row, field) triples the write actually carried: a field the write never
     * mentioned makes no statement about itself, which is what keeps a partial update from unclaiming
     * everything the row holds.
     *
     * <p>The binding goes; the model it was uploaded against stays. Clearing that too would make the
     * file claimable by a row of any model, so removing an attachment would widen its exposure rather
     * than return it to neutral — the opposite of what a removal means.
     */
    private void releaseVacatedSlots(Collection<FileClaim> claims, Collection<FileSlot> slots) {
        if (CollectionUtils.isEmpty(slots)) {
            return;
        }
        Set<Long> stillClaimed = claims == null ? Set.of()
                : claims.stream().map(FileClaim::fileId).filter(Objects::nonNull).collect(Collectors.toSet());
        // Grouped by (model, field) with the row ids as an IN list rather than one OR branch per slot:
        // a bulk write of a thousand rows would otherwise hand the SQL builder a thousand-deep nested
        // OR tree. One write touches one model and a handful of file fields, so this stays a couple of
        // clauses whatever the row count.
        Map<String, List<String>> rowIdsBySlot = new LinkedHashMap<>();
        Map<String, FileSlot> slotByKey = new LinkedHashMap<>();
        for (FileSlot slot : slots) {
            String key = slot.modelName() + '\u0000' + slot.fieldName();
            slotByKey.putIfAbsent(key, slot);
            rowIdsBySlot.computeIfAbsent(key, k -> new ArrayList<>()).add(slot.rowId());
        }
        List<Filters> groups = new ArrayList<>(slotByKey.size());
        slotByKey.forEach((key, slot) -> groups.add(new Filters()
                .eq(FileRecord::getModelName, slot.modelName())
                .eq(FileRecord::getFieldName, slot.fieldName())
                .in(FileRecord::getRowId, rowIdsBySlot.get(key))));
        Filters slotFilters = groups.size() == 1
                ? groups.getFirst()
                : Filters.or(groups.getFirst(), groups.get(1),
                        groups.subList(2, groups.size()).toArray(new Filters[0]));
        List<FileRecord> bound = bypassFileRecordScope(() -> this.searchList(slotFilters));
        List<FileRecord> released = new ArrayList<>();
        for (FileRecord record : bound) {
            if (stillClaimed.contains(record.getId())) {
                continue;
            }
            record.setRowId(null);
            record.setFieldName(null);
            released.add(record);
        }
        if (!released.isEmpty()) {
            // ignoreNull = false, and that is the whole point: a release IS the writing of nulls. The
            // one-argument updateList drops them, so it would issue an update that changed nothing and
            // leave every released file bound to its old row — passing every test that stubs the write.
            bypassFileRecordScope(() -> this.updateList(released, false));
        }
    }

    /**
     * Write the FileRecord itself, without asking whether the caller may create FileRecords.
     *
     * <p>Nobody grants that permission and nobody should have to: a file record is bookkeeping for an
     * action the caller was already authorized to perform — attaching to a row they may edit,
     * downloading an import template, exporting a list. Every value on it is server-derived (oss key,
     * checksum, size, the model and row it was uploaded against), so there is no caller-supplied input
     * here for the skip to widen.
     */
    private Long persistFileRecord(FileRecord fileRecord) {
        // Stamp the uploading tenant. FileRecord is not multiTenant, so the write pipeline does not
        // fill tenantId; the unclaimed-claim check (sameTenantAsCaller) is what reads it back.
        if (fileRecord.getTenantId() == null) {
            fileRecord.setTenantId(ContextHolder.getContext().getTenantId());
        }
        fileRecord.setFileName(fitFileName(fileRecord.getFileName()));
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
        // copy anything. FileRecord is NOT multi-tenant — this read carries no tenant predicate, so a
        // cross-tenant fileId would resolve and be copied. Same-tenant is the caller's guarantee, not
        // this method's: the only caller (confirmHire) obtained the source id by reading a row under
        // its own tenant scope. Keep it that way — do not expose copyFileTo to a caller that hands it
        // a fileId it did not first resolve through a tenant-scoped business read.
        return bypassFileRecordScope(() -> this.getById(fileId)).map(source -> {
            FileRecord copy = new FileRecord();
            // A stored object of its own, under a key of the new owner's model: oss_key is unique, one
            // record per object, so the copy cannot share the source's key. A record with no object yet
            // has nothing to copy.
            if (StringUtils.isNotBlank(source.getOssKey())) {
                String ossKey = this.generateOssKey(modelName, source.getFileName());
                ossClientService.copyObject(source.getOssKey(), ossKey, source.getFileName());
                copy.setOssKey(ossKey);
            }
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

    /**
     * Run a FileRecord read or write with FileRecord's own row-scope waived — and only its own.
     *
     * <p>FileRecord is anchorless: no scope rule can name it and no business model references it, so
     * its row-scope collapses to {@code matchNone()} for any non-admin. On a table whose access is
     * meant to derive from the row each file hangs on that is not merely redundant — it does not fail
     * safe, it fails <em>shut</em>: {@code getById} throws for every non-admin, so an employee cannot
     * read their own attachment and HR cannot read the employee's.
     *
     * <p>What the design puts in its place is upstream: the business row's own read decides, and
     * {@link #assertClaimable} guarantees the column that led here names a file this row owns. Waiving
     * FileRecord's scope removes an obstacle, not a control.
     */
    private <T> T bypassFileRecordScope(java.util.function.Supplier<T> action) {
        // getContext() never returns null — an unbound thread gets a throwaway Context, and with no
        // context the permission layer already bypasses, so there is nothing to branch on.
        Context context = ContextHolder.getContext();
        boolean previous = context.isSkipPermissionCheck();
        try {
            context.setSkipPermissionCheck(true);
            return action.get();
        } finally {
            context.setSkipPermissionCheck(previous);
        }
    }
}
