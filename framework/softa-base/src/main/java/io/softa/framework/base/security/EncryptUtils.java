package io.softa.framework.base.security;

import java.util.HashMap;
import java.util.Map;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

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
     * Tells whether a stored value is ciphertext, without decrypting it.
     * Use it to skip values that are already encrypted, and to find plaintext left in an encrypted
     * column - neither needs the encryption key. A blank value is never ciphertext.
     *
     * @param value the stored value
     * @return true if the value is ciphertext
     */
    public static boolean isCiphertext(String value) {
        return StringUtils.isNotBlank(value) && encryptor.isCiphertext(value);
    }

    /**
     * Single ciphertext decryption
     * If the value is not ciphertext at all (such as plaintext passed in), the original text is
     * returned; ciphertext that does not decrypt under the configured key raises an exception.
     *
     * @param ciphertext ciphertext
     * @return plaintext
     */
    public static String decrypt(String ciphertext) {
        if (StringUtils.isNotBlank(ciphertext)) {
            try {
                return encryptor.decrypt(ciphertext, password);
            } catch (SystemException e) {
                // Already diagnosed by the algorithm, do not bury it under a second message
                throw e;
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
            } catch (SystemException e) {
                // Already diagnosed by the algorithm, do not bury it under a second message
                throw e;
            } catch (Exception e) {
                throw new SystemException("Batch decrypts exception!", e);
            }
        }
        return new HashMap<>();
    }

    /**
     * Compute SHA-256 hash of the input content
     *
     * @deprecated a digest is not encryption: use {@link HashUtils#sha256Hex(String)}, which this calls and
     * which gives the same lowercase hex.
     */
    @Deprecated
    public static String computeSha256(String content) {
        return HashUtils.sha256Hex(content);
    }
}
