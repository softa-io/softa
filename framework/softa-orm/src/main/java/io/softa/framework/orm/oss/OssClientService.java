package io.softa.framework.orm.oss;

import java.io.InputStream;

public interface OssClientService {

    /**
     * Uploads the file stream to the OSS bucket
     *
     * @param ossKey   the key to store the file in the OSS bucket
     * @param inputStream the input stream of the file
     * @param fileName file name
     * @return the checksum of the uploaded file
     */
    String uploadStreamToOSS(String ossKey, InputStream inputStream, String fileName);

    /**
     * Generates a pre-signed URL for the file
     *
     * @param ossKey             the key of the file stored in the OSS bucket
     * @param fileName           file name
     * @return the pre-signed URL
     */
    String getPreSignedUrl(String ossKey, String fileName);

    /**
     * Generates a pre-signed URL for the file
     *
     * @param ossKey             the key of the file stored in the OSS bucket
     * @param expirationInSeconds the duration for which the URL is valid
     * @param fileName           file name
     * @return the pre-signed URL
     */
    String getPreSignedUrl(String ossKey, int expirationInSeconds, String fileName);

    /**
     * Generates a pre-signed URL for forced download.
     *
     * @param ossKey the key of the file stored in the OSS bucket
     * @param fileName file name
     * @return the pre-signed URL
     */
    String getDownloadUrl(String ossKey, String fileName);

    /**
     * Generates a pre-signed URL for forced download.
     *
     * @param ossKey the key of the file stored in the OSS bucket
     * @param expirationInSeconds the duration for which the URL is valid
     * @param fileName file name
     * @return the pre-signed URL
     */
    String getDownloadUrl(String ossKey, int expirationInSeconds, String fileName);

    /**
     * Copies a stored object to a new key in the same bucket, on the storage side: the bytes are not
     * read through this process.
     *
     * @param sourceKey the key of the object to copy
     * @param targetKey the key the copy is stored under
     * @param fileName  file name, for the error message
     */
    void copyObject(String sourceKey, String targetKey, String fileName);

    /**
     * Downloads the file from the OSS bucket and returns the InputStream
     *
     * @param ossKey the key of the file stored in the OSS bucket
     * @return the InputStream of the file
     */
    InputStream downloadStreamFromOSS(String ossKey, String fileName);
}
