package io.softa.framework.base.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.SpringContextUtils;


@Component
@DependsOn("springContextUtils")
public class EncryptUtils {

    // Default use AESEncryption
    @Value("${security.encryption.algorithm:AESEncryption}")
    private String algorithm;

    @Value("${security.encryption.password}")
    private String encryptionPassword;

    private static Encryptor encryptor;
    private static String password;

    @PostConstruct
    public void init() {
        encryptor = SpringContextUtils.getBeanByName(algorithm);
        Assert.notBlank(encryptionPassword, "Encryption password cannot be null!");
        password = encryptionPassword;
    }

    /**
     * Single plaintext encryption
     *
     * @param plaintext plaintext
     * @return ciphertext
     */
    public static String encrypt(String plaintext) {
        if (StringUtils.isNotBlank(plaintext)) {
            try {
                return encryptor.encrypt(plaintext, password);
            } catch (Exception e) {
                throw new SystemException("Encrypt exception!", e);
            }
        }
        return plaintext;
    }

    /**
     * Single ciphertext decryption
     * If decryption fails (such as plaintext passed in), the original text is returned.
     *
     * @param ciphertext ciphertext
     * @return plaintext
     */
    public static String decrypt(String ciphertext) {
        if (StringUtils.isNotBlank(ciphertext)) {
            try {
                return encryptor.decrypt(ciphertext, password);
            } catch (Exception e) {
                throw new SystemException("Decrypt exception!", e);
            }
        }
        return ciphertext;
    }

    /**
     * Batch encryption
     *
     * @param plaintextMap plaintext Map
     * @return Map<index, ciphertext>
     */
    public static Map<Integer, String> encrypt(Map<Integer, String> plaintextMap) {
        if (!CollectionUtils.isEmpty(plaintextMap)) {
            try {
                return encryptor.encrypt(plaintextMap, password);
            } catch (Exception e) {
                throw new SystemException("Batch encrypts exception!", e);
            }
        }
        return new HashMap<>();
    }

    /**
     * Batch decryption
     *
     * @param ciphertextMap ciphertext Map
     * @return Map<index, plaintext>
     */
    public static Map<Integer, String> decrypt(Map<Integer, String> ciphertextMap) {
        if (!CollectionUtils.isEmpty(ciphertextMap)) {
            try {
                return encryptor.decrypt(ciphertextMap, password);
            } catch (Exception e) {
                throw new SystemException("Batch decrypts exception!", e);
            }
        }
        return new HashMap<>();
    }

    /**
     * Compute SHA-256 hash of the input content
     */
    public static String computeSha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException("SHA-256 algorithm not available", e);
        }
    }
}
