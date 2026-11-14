package io.softa.framework.orm.oss.impl;

import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import com.aliyun.oss.model.ResponseHeaderOverrides;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.exception.ExternalException;
import io.softa.framework.base.exception.SystemException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.enums.FileType;
import io.softa.framework.orm.oss.ContentDisposition;
import io.softa.framework.orm.oss.OSSProperties;
import io.softa.framework.orm.oss.OssClientService;

/**
 * Aliyun OSS Client Service Implementation
 */
@Slf4j
@AllArgsConstructor
public class AliyunOSSClientService implements OssClientService {

    private OSS ossClient;

    // Signs the URLs the browser fetches — see AliyunOSSConfig#presignOss. Same instance as ossClient
    // unless `oss.presign-endpoint` names a different address.
    private OSS presignClient;

    private OSSProperties ossProperties;

    private String getBucketName() {
        return ossProperties.getBucketName();
    }

    /**
     * Uploads the file stream to the OSS bucket
     *
     * @param ossKey   the key to store the file in the OSS bucket
     * @param inputStream the input stream of the file
     * @param fileName file name
     * @return the checksum of the uploaded file
     */
    @Override
    public String uploadStreamToOSS(String ossKey, InputStream inputStream, String fileName) {
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(FileType.contentTypeOf(ossKey));
            PutObjectResult objectResult = ossClient.putObject(getBucketName(), ossKey, inputStream, metadata);
            return objectResult.getETag();
        } catch (OSSException ossException) {
            throw new ExternalException("OSSException occurred while uploading the file {0}.\n{1}",
                    fileName, ossException.getErrorMessage(), ossException);
        } catch (ClientException clientException) {
            throw new SystemException("ClientException occurred while uploading the file {0}.\n{1}",
                    fileName, clientException.getMessage(), clientException);
        } catch (Exception e) {
            throw new SystemException("Unexpected error occurred while uploading the file {0}.\n{1}",
                    fileName, e.getMessage(), e);
        }
    }

    /**
     * Generates a pre-signed URL for the file
     *
     * @param ossKey             the key of the file stored in the OSS bucket
     * @param fileName           file name
     * @return the pre-signed URL
     */
    @Override
    public String getPreSignedUrl(String ossKey, String fileName) {
        int expirationInSeconds = ossProperties.getUrlExpireSeconds() == null ?
                FileConstant.DEFAULT_DOWNLOAD_URL_EXPIRE : ossProperties.getUrlExpireSeconds();
        return getPreSignedUrl(ossKey, expirationInSeconds, fileName);
    }

    /**
     * Generates a pre-signed URL for the file
     *
     * @param ossKey             the key of the file stored in the OSS bucket
     * @param expirationInSeconds the duration for which the URL is valid
     * @param fileName           file name
     * @return the pre-signed URL
     */
    @Override
    public String getPreSignedUrl(String ossKey, int expirationInSeconds, String fileName) {
        try {
            Date expiration = new Date(System.currentTimeMillis() + expirationInSeconds * 1000L);
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(getBucketName(), ossKey);
            request.setExpiration(expiration);
            ResponseHeaderOverrides responseHeaders = new ResponseHeaderOverrides();
            responseHeaders.setContentDisposition(ContentDisposition.of(ContentDisposition.INLINE, fileName));
            request.setResponseHeaders(responseHeaders);
            URL url = presignClient.generatePresignedUrl(request);
            return url.toString();
        } catch (Exception e) {
            throw new ExternalException("Error while generating the file URL {0}", fileName, e);
        }
    }

    @Override
    public String getDownloadUrl(String ossKey, String fileName) {
        int expirationInSeconds = ossProperties.getUrlExpireSeconds() == null ?
                FileConstant.DEFAULT_DOWNLOAD_URL_EXPIRE : ossProperties.getUrlExpireSeconds();
        return getDownloadUrl(ossKey, expirationInSeconds, fileName);
    }

    @Override
    public String getDownloadUrl(String ossKey, int expirationInSeconds, String fileName) {
        try {
            Date expiration = new Date(System.currentTimeMillis() + expirationInSeconds * 1000L);
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(getBucketName(), ossKey);
            request.setExpiration(expiration);
            ResponseHeaderOverrides responseHeaders = new ResponseHeaderOverrides();
            responseHeaders.setContentDisposition(ContentDisposition.of(ContentDisposition.ATTACHMENT, fileName));
            request.setResponseHeaders(responseHeaders);
            URL url = presignClient.generatePresignedUrl(request);
            return url.toString();
        } catch (Exception e) {
            throw new ExternalException("Error while generating the download URL {0}", fileName, e);
        }
    }

    @Override
    public void copyObject(String sourceKey, String targetKey, String fileName) {
        try {
            ossClient.copyObject(getBucketName(), sourceKey, getBucketName(), targetKey);
        } catch (OSSException ossException) {
            throw new ExternalException("OSSException occurred while copying the file {0}.\n{1}",
                    fileName, ossException.getErrorMessage(), ossException);
        } catch (ClientException clientException) {
            throw new SystemException("ClientException occurred while copying the file {0}.\n{1}",
                    fileName, clientException.getMessage(), clientException);
        } catch (Exception e) {
            throw new SystemException("Unexpected error occurred while copying the file {0}.\n{1}",
                    fileName, e.getMessage(), e);
        }
    }

    /**
     * Downloads the file from the OSS bucket and returns the InputStream
     *
     * @param ossKey the key of the file stored in the OSS bucket
     * @return the InputStream of the file
     */
    @Override
    public InputStream downloadStreamFromOSS(String ossKey, String fileName) {
        try {
            OSSObject ossObject = ossClient.getObject(getBucketName(), ossKey);
            return ossObject.getObjectContent();
        } catch (OSSException ossException) {
            throw new ExternalException("OSSException occurred while downloading the file {0}.\n{1}",
                    fileName, ossException.getErrorMessage(), ossException);
        } catch (ClientException clientException) {
            throw new SystemException("ClientException occurred while downloading the file {0}.\n{1}",
                    fileName, clientException.getMessage(), clientException);
        } catch (Exception e) {
            throw new SystemException("Unexpected error occurred while uploading the file {0}.\n{1}",
                    fileName, e.getMessage(), e);
        }
    }

}
