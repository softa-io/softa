package io.softa.framework.orm.utils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.dto.DownloadFileDTO;
import io.softa.framework.orm.enums.FileType;

/**
 * HTTP download utility class
 * Provides secure URL download functionality, including URL validation, connection management, and file download.
 */
@Slf4j
public class HttpDownloadUtils {

    /** HTTP connection timeout: 2 seconds */
    private static final int CONNECT_TIMEOUT = (int) TimeUnit.SECONDS.toMillis(2);

    /** HTTP read timeout: 10 seconds */
    private static final int READ_TIMEOUT = (int) TimeUnit.SECONDS.toMillis(5);

    /** Allowed URL protocols */
    private static final Set<String> ALLOWED_PROTOCOLS = Set.of("http", "https");

    /** Default User-Agent */
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

    /** BufferedInputStream buffer size: 64KB, enough for file type detection */
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Download file from URL
     *
     * @param url file URL
     * @return download result, including input stream and file information
     * @throws BusinessException when URL is invalid or download fails
     */
    public static DownloadFileDTO downloadFromUrl(String url) {
        validateUrl(url);

        HttpURLConnection connection = null;
        // Create BufferedInputStream, automatically supports mark/reset
        BufferedInputStream bufferedInputStream = null;

        try {
            // Create HTTP connection
            connection = createConnection(url);

            // Validate response status
            validateResponse(connection, url);
            // Get file name
            String fileName = extractFileName(url, connection);

            // 1. Create BufferedInputStream, automatically supports mark/reset
            bufferedInputStream = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);

            // 2. Get the actual file size (calculated by reading the stream, not dependent on Content-Length)
            int actualFileSize = calculateActualFileSize(bufferedInputStream);

            // 3. Mark the current position for reset
            bufferedInputStream.mark(BUFFER_SIZE);

            // 4. Detect the actual type of the file (not dependent on HTTP headers)
            FileType fileType = FileUtils.getActualFileType(fileName, bufferedInputStream);

            // 5. Reset the stream to the marked position
            bufferedInputStream.reset();

            return new DownloadFileDTO(bufferedInputStream, fileName, fileType, actualFileSize, connection);

        } catch (BusinessException e) {
            // Business exception directly thrown
            if (bufferedInputStream != null) {
                try {
                    bufferedInputStream.close();
                } catch (IOException ex) {
                    log.warn("Failed to close input stream", ex);
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
            throw e;
        } catch (IOException e) {
            // IO exception wrapped as business exception
            if (bufferedInputStream != null) {
                try {
                    bufferedInputStream.close();
                } catch (IOException ex) {
                    log.warn("Failed to close input stream", ex);
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
            throw new BusinessException("Failed to download file from URL: {0}", e.getMessage(), e);
        }
    }

    /**
     * Calculate the actual size of the file
     *
     * @param bufferedInputStream input stream
     * @return file size (KB)
     * @throws IOException IO exception
     */
    private static int calculateActualFileSize(BufferedInputStream bufferedInputStream) throws IOException {
        long totalBytes = 0;
        byte[] buffer = new byte[64 * 1024];
        int bytesRead;
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
        // Convert to KB
        int fileSizeKB = (int) (totalBytes / 1024);
        if (totalBytes % 1024 > 0) {
            fileSizeKB++; // Round up
        }
        return fileSizeKB;
    }

    /**
     * Validate URL security
     *
     * @param urlString URL string
     * @throws BusinessException when URL is not secure
     */
    public static void validateUrl(String urlString) {
        if (!StringUtils.isNotBlank(urlString)) {
            throw new BusinessException("URL is empty");
        }
        try {
            URL url = URI.create(urlString).toURL();

            // Validate protocol
            String protocol = url.getProtocol().toLowerCase();
            if (!ALLOWED_PROTOCOLS.contains(protocol)) {
                throw new BusinessException("Unsupported URL protocol: {0}, only support {1}",
                        protocol, ALLOWED_PROTOCOLS);
            }

            // Validate host
            String host = url.getHost();
            if (host == null || host.trim().isEmpty()) {
                throw new BusinessException("Invalid URL host");
            }

            // Prevent access to localhost and private addresses
            if (isLocalOrPrivateAddress(host)) {
                throw new BusinessException("Access to private address is not allowed: {0}", host);
            }

        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new BusinessException("Invalid URL format: {0}", urlString);
        }
    }

    /**
     * Check if the host is a local or private address
     */
    private static boolean isLocalOrPrivateAddress(String host) {
        return host.equalsIgnoreCase("localhost") ||
                host.equals("127.0.0.1") ||
                host.equals("0.0.0.0") ||
                host.startsWith("192.168.") ||
                host.startsWith("10.") ||
                host.startsWith("172.16.") ||
                host.startsWith("172.17.") ||
                host.startsWith("172.18.") ||
                host.startsWith("172.19.") ||
                host.startsWith("172.2") ||
                host.startsWith("172.30.") ||
                host.startsWith("172.31.");
    }

    /**
     * Create HTTP connection
     *
     * @param urlString URL string
     * @return HTTP connection
     * @throws IOException IO exception
     */
    private static HttpURLConnection createConnection(String urlString) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(urlString).toURL().openConnection();

        // Set request properties
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setInstanceFollowRedirects(true);

        // Set User-Agent, simulate normal browser request
        connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);

        return connection;
    }

    /**
     * Validate HTTP response
     *
     * @param connection HTTP connection
     * @param url URL string
     * @throws IOException IO exception
     * @throws BusinessException business exception
     */
    private static void validateResponse(HttpURLConnection connection, String url) throws IOException {
        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new BusinessException("Cannot access URL: {0}, HTTP status code: {1}", url, responseCode);
        }
    }

    /**
     * Extract the file name from the URL or HTTP response header
     *
     * @param url URL string
     * @param connection HTTP connection
     * @return file name
     */
    private static String extractFileName(String url, HttpURLConnection connection) {
        // Try to get the file name from the Content-Disposition header first
        String contentDisposition = connection.getHeaderField("Content-Disposition");
        if (contentDisposition != null && contentDisposition.contains("filename=")) {
            String fileName = extractFileNameFromContentDisposition(contentDisposition);
            if (StringUtils.isNotBlank(fileName)) {
                return fileName;
            }
        }

        // Extract the file name from the URL path
        String path = URI.create(url).getPath();
        if (StringUtils.isNotBlank(path)) {
            String fileName = FilenameUtils.getName(path);
            if (StringUtils.isNotBlank(fileName)) {
                return fileName;
            }
        }

        // Default file name
        return "downloaded_file";
    }

    /**
     * Extract the file name from the Content-Disposition header
     */
    private static String extractFileNameFromContentDisposition(String contentDisposition) {
        try {
            // Handle filename= and filename*= formats
            String fileName = null;

            if (contentDisposition.contains("filename*=")) {
                // RFC 5987 encoding format: filename*=UTF-8''%E6%96%87%E4%BB%B6%E5%90%8D.txt
                int start = contentDisposition.indexOf("filename*=") + 10;
                String encoded = contentDisposition.substring(start);
                if (encoded.contains("''")) {
                    fileName = encoded.substring(encoded.indexOf("''") + 2);
                    fileName = java.net.URLDecoder.decode(fileName, StandardCharsets.UTF_8);
                }
            } else if (contentDisposition.contains("filename=")) {
                // Standard format: filename="file name.txt" or filename=file name.txt
                int start = contentDisposition.indexOf("filename=") + 9;
                fileName = contentDisposition.substring(start);
                if (fileName.startsWith("\"") && fileName.endsWith("\"")) {
                    fileName = fileName.substring(1, fileName.length() - 1);
                }
            }

            return fileName;
        } catch (Exception e) {
            log.warn("Failed to parse Content-Disposition: {}", contentDisposition, e);
            return null;
        }
    }
}