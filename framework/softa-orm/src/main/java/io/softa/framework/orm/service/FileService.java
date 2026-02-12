package io.softa.framework.orm.service;

import java.io.InputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.dto.UploadFileDTO;

public interface FileService {

    /**
     * Upload a file to the OSS and create a corresponding FileRecord to associate
     * with a business model and rowId.
     *
     * @param modelName the name of the corresponding business model
     * @param file      the file to be uploaded
     * @return fileInfo object
     */
    FileInfo uploadFile(String modelName, MultipartFile file);

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
    FileInfo uploadFile(String modelName, Serializable rowId, String fieldName, MultipartFile file);

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
    FileInfo uploadFromUrl(String modelName, Serializable rowId, String fieldName, String url, int expireSeconds);

    /**
     * Upload a file to the OSS by input stream.
     * The uploadFileDTO contains the file information and input stream.
     *
     * @param uploadFileDTO the upload file DTO
     * @return filInfo object containing the download URL and metadata of the uploaded file
     */
    FileInfo uploadFromStream(UploadFileDTO uploadFileDTO);

    /**
     * Download the file stream from the OSS bucket by fileId
     *
     * @param fileId the ID of the file to be downloaded
     * @return the InputStream of the file
     */
    InputStream downloadStream(Long fileId);

    /**
     * Get the FileInfo object by fileId
     *
     * @param fileId the ID of the file
     * @return Optional object containing the FileInfo object if found, or empty if not found
     */
    Optional<FileInfo> getByFileId(Long fileId);

    /**
     * Get the FileInfo object by fileId
     *
     * @param fileId the ID of the file
     * @param expireSeconds the expiration time in seconds
     * @return Optional object containing the FileInfo object if found, or empty if not found
     */
    Optional<FileInfo> getByFileId(Long fileId, int expireSeconds);

    /**
     * Get the FileInfo object list by fileIds.
     *
     * @param fileIds the file IDs
     * @return the list of FileInfo objects
     */
    List<FileInfo> getByFileIds(List<Long> fileIds);

    /**
     * Get the FileInfo object by modelName and rowId
     *
     * @param modelName the name of the corresponding business model
     * @param rowId the ID of the corresponding business row data
     * @return fileInfo object with download URL
     */
    List<FileInfo> getRowFiles(String modelName, Serializable rowId);

}
