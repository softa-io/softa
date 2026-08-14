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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.constant.FileConstant;
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
        Long id = this.createOne(fileRecord);
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
        Long id = this.createOne(fileRecord);
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

            Long id = this.createOne(fileRecord);
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
        FileRecord fileRecord = this.getById(fileId)
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
        Optional<FileRecord> fileRecordOpt = this.getById(fileId);
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
        Optional<FileRecord> fileRecordOpt = this.getById(fileId);
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
        List<FileRecord> fileRecords = this.getByIds(fileIds);
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
        permissionService.checkIdAccess(modelName, rowId, AccessType.READ);
        Filters filters = new Filters()
                .eq(FileRecord::getModelName, modelName)
                .eq(FileRecord::getRowId, rowId.toString());
        List<FileRecord> fileRecords = this.searchList(filters);
        return fileRecords.stream().map(this::convertToFileInfo).toList();
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
    public void claimFiles(Collection<FileClaim> claims) {
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
        List<FileRecord> records = this.getByIds(new ArrayList<>(claimById.keySet()));
        List<FileRecord> toUpdate = new ArrayList<>(records.size());
        for (FileRecord record : records) {
            FileClaim claim = claimById.get(record.getId());
            if (claim == null || isAlreadyClaimed(record, claim)) {
                continue;
            }
            record.setModelName(claim.modelName());
            record.setRowId(claim.rowId());
            record.setFieldName(claim.fieldName());
            toUpdate.add(record);
        }
        if (!toUpdate.isEmpty()) {
            this.updateList(toUpdate);
        }
    }

    /** True when the record already carries exactly this binding — re-saving a row must not churn writes. */
    private boolean isAlreadyClaimed(FileRecord record, FileClaim claim) {
        return Objects.equals(record.getModelName(), claim.modelName())
                && Objects.equals(record.getRowId(), claim.rowId())
                && Objects.equals(record.getFieldName(), claim.fieldName());
    }
}