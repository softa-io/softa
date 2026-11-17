package io.softa.framework.orm.utils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.AssertBusiness;
import io.softa.framework.orm.domain.FileObject;
import io.softa.framework.orm.domain.FileStream;
import io.softa.framework.orm.enums.FileType;

/**
 * Utility class for file operations
 */
@Slf4j
public class FileUtils {

    /** BufferedInputStream buffer size: 64KB, enough for file type detection */
    private static final int BUFFER_SIZE = 64 * 1024;
    public static final long MAX_FILE_SIZE = 100 * 1024 * 1024;

    /**
     * Gets the extension of a file.
     *
     * @param fileName fileName, supports relative or absolute paths
     * @return The file extension.
     */
    private static FileType getFileTypeByExtension(String fileName) {
        Assert.notBlank(fileName, "Filename cannot be empty!");
        try {
            String extension = FilenameUtils.getExtension(fileName);
            Assert.notBlank(extension, "File has no extension!");
            Optional<FileType> fileType = FileType.ofExtension(extension);
            return fileType.orElseThrow(() -> new BusinessException(
                    "The file {0} is not supported. Its extension is: {1}.", fileName, extension));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse the extension of file: {0}!", fileName);
        }
    }

    /**
     * Gets the fileObject by path.
     *
     * @param path     The path of the file
     * @param fileName The name of the file
     * @return The fileObject with the file content
     */
    public static FileObject getFileObjectByPath(String path, String fileName) {
        Assert.notBlank(fileName, "Filename cannot be empty!");
        String fullName = path + fileName;
        ClassPathResource resource = new ClassPathResource(fullName);
        Assert.isTrue(resource.exists(), "File does not exist: {0}", fullName);
        FileObject fileObject = new FileObject();
        try (InputStream inputStream = resource.getInputStream()) {
            FileType seemingFileType = getFileTypeByExtension(fullName);
            FileType actualFileType = getActualFileType(fullName, inputStream, seemingFileType);
            validateFileType(fullName, actualFileType, seemingFileType);
            fileObject.setFileType(actualFileType);
            fileObject.setFileName(fileName);
            fileObject.setContent(resource.getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read the content of file: {0}", fullName, e);
        }
        return fileObject;
    }

    /**
     * Validates if the file type, determined by its real mimetype, is within the acceptable range.
     *
     * @param file The MultipartFile to validate
     * @return The fileObject object
     */
    public static FileObject getFileObject(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("File too large: {0} bytes", file.getSize());
        }
        FileObject fileObject = new FileObject();
        fileObject.setFileName(file.getOriginalFilename());
        fileObject.setFileType(getActualFileType(file));
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            fileObject.setContent(content);
            return fileObject;
        } catch (IOException e) {
            throw new BusinessException("Failed to read the uploaded file!", e);
        }
    }

    /**
     * Gets the fileName without extension.
     *
     * @param file The uploaded multipart file object
     * @return The fileName without extension
     */
    public static String getShortFileName(MultipartFile file) {
        String originalFileName = file.getOriginalFilename();
        if (originalFileName != null && originalFileName.contains(".")) {
            // Get the index of the last dot
            int dotIndex = originalFileName.lastIndexOf('.');
            return originalFileName.substring(0, dotIndex);
        }
        // If the file has no extension or the name is empty, return the original name
        return originalFileName;
    }

    /**
     * Gets the actual fileType of the uploaded file: what its content is, checked against what it claims to be.
     *
     * <p>It makes two claims, its content type and its name, and the content has to agree with either. The content
     * type is the browser's guess, and browsers guess differently for the same file — a Word document has arrived
     * as {@code application/zip}, {@code application/msword} or with no type at all — so a file whose content
     * matches its own name is taken as what it is. One whose content matches neither claim is refused: that is a
     * file renamed to pass for another type.
     *
     * @param file The uploaded file
     * @return The actual fileType
     */
    public static FileType getActualFileType(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        String contentType = file.getContentType();
        Optional<FileType> declared = contentType == null || contentType.isBlank()
                ? Optional.empty() : FileType.of(contentType);
        Optional<FileType> named = extensionTypeOf(fileName);
        if (declared.isEmpty() && named.isEmpty()) {
            throw new BusinessException("The file {0} is not supported. Its content type is: {1}.",
                    fileName, contentType);
        }
        FileType actualFileType;
        try (InputStream inputStream = file.getInputStream()) {
            actualFileType = getActualFileType(fileName, inputStream, null);
        } catch (IOException e) {
            throw new BusinessException("Failed to read the uploaded file!", e);
        }
        if (named.filter(type -> isCompatible(actualFileType, type)).isPresent()) {
            return actualFileType;
        }
        validateFileType(fileName, actualFileType, declared.orElseGet(named::get));
        return actualFileType;
    }

    /** The type the file's extension names, or empty when it has none or names no supported type. */
    private static Optional<FileType> extensionTypeOf(String fileName) {
        String extension = fileName == null ? null : FilenameUtils.getExtension(fileName);
        return extension == null || extension.isBlank()
                ? Optional.empty() : FileType.ofExtension(extension.toLowerCase(Locale.ROOT));
    }

    /**
     * Gets the actual fileType of the uploaded file.
     *
     * @param fileName fileName, using relative paths
     * @return The actual fileType
     */
    private static FileType getActualFileType(String fileName, InputStream inputStream, FileType seemingFileType) {
        try {
            Metadata fileMetadata = new Metadata();
            fileMetadata.set("resourceName", fileName);
            if (inputStream.markSupported()) {
                inputStream.mark(BUFFER_SIZE);
            }
            String mimetype = new Tika().detect(inputStream, fileMetadata);
            if (inputStream.markSupported()) {
                inputStream.reset();
            }
            FileType actualFileType = FileType.of(mimetype)
                    .orElseThrow(() -> new BusinessException(
                            "The file {0} is not supported. Its actual file type (MimeType) is: {1}.",
                            fileName, mimetype));
            if (seemingFileType != null) {
                validateFileType(fileName, actualFileType, seemingFileType);
            }
            return actualFileType;
        } catch (BusinessException e) {
            // The file was read: what is wrong with it is said as it is, not as a failure to read.
            throw e;
        } catch (Throwable e) {
            throw new BusinessException("Failed to read the uploaded file!", e);
        }
    }

    /**
     * Gets the actual fileType of the uploaded file.
     *
     * @param fileName fileName, using relative paths
     * @return The actual fileType
     */
    public static FileStream getFileStream(String fileName, InputStream inputStream) {
        // 1. Create BufferedInputStream, automatically supports mark/reset
        BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream, BUFFER_SIZE);

        // 2. Detect the actual type of the file (not dependent on HTTP headers)
        FileType actualFileType = getActualFileType(fileName, bufferedInputStream, null);

        // 3. Get the actual file size (calculated by reading the stream, not dependent on Content-Length)
        int actualFileSize = calculateActualFileSize(fileName, bufferedInputStream);

        // 4. Return the FileStream object with the actual file type and size
        FileStream fileStream = new FileStream();
        fileStream.setFileName(fileName);
        fileStream.setFileType(actualFileType);
        fileStream.setFileSize(actualFileSize);
        fileStream.setInputStream(bufferedInputStream);
        return fileStream;
    }

    /**
     * Validates if the file type, determined by its real mimetype, is within the acceptable range.
     *
     * @param fileName        The name of the file
     * @param actualFileType  The actual fileType
     * @param seemingFileType The seeming fileType
     */
    private static void validateFileType(String fileName, FileType actualFileType, FileType seemingFileType) {
        AssertBusiness.isTrue(isCompatible(actualFileType, seemingFileType), """
                The file {0} with actual type {1} does not match the uploaded file type {2}. Please contact the
                system administrator if you have any questions.""", fileName, actualFileType, seemingFileType);
    }

    /** Whether content of {@code actualFileType} may be taken as {@code seemingFileType}. */
    private static boolean isCompatible(FileType actualFileType, FileType seemingFileType) {
        // Allow different image types and handle cases where the image extension was modified.
        if (FileType.COMPATIBLE_IMAGE_TYPE.contains(actualFileType)
                && FileType.COMPATIBLE_IMAGE_TYPE.contains(seemingFileType)) {
            return true;
        }
        // Allow different text types and handle cases where the text extension was modified.
        if (FileType.COMPATIBLE_TEXT_TYPE.contains(actualFileType)
                && FileType.COMPATIBLE_TEXT_TYPE.contains(seemingFileType)) {
            return true;
        }
        return actualFileType.equals(seemingFileType);
    }

    /**
     * Calculate the actual size of the file
     *
     * @param bufferedInputStream input stream
     * @return file size (KB)
     */
    private static int calculateActualFileSize(String fileName, BufferedInputStream bufferedInputStream) {
        long totalBytes = 0;
        byte[] buffer = new byte[64 * 1024];
        int bytesRead;
        try {
            // Mark the current position for reset
            bufferedInputStream.mark(BUFFER_SIZE);
            while ((bytesRead = bufferedInputStream.read(buffer)) != -1) {
                totalBytes += bytesRead;
                // Check if the file size exceeds the limit
                if (totalBytes > BaseConstant.DEFAULT_FILE_SIZE_LIMIT) {
                    throw new BusinessException("File size exceeds the limit: {0} bytes, maximum allowed: {1} bytes",
                            totalBytes, BaseConstant.DEFAULT_FILE_SIZE_LIMIT);
                }
            }
            // Reset the stream to the marked position
            bufferedInputStream.reset();
        } catch (IOException e) {
            throw new BusinessException("Failed to read the uploaded file: {0}", fileName, e);
        }
        // Convert to KB
        int fileSizeKB = (int) (totalBytes / 1024);
        if (totalBytes % 1024 > 0) {
            fileSizeKB++; // Round up
        }
        return fileSizeKB;
    }

}
