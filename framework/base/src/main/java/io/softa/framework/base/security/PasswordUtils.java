package io.softa.framework.base.security;

import io.softa.framework.base.exception.SystemException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public class PasswordUtils {

    /**
     * Generate a 16-bit random salt, 32 hexadecimal characters
     */
    public static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return bytesToHex(salt);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Hash the password with the 16bit salt using SHA-512 algorithm
     * @param password plaintext password
     * @param salt 16-bit random salt
     * @return hashed password
     */
    public static String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            md.update((salt + password.trim()).getBytes());
            byte[] hashedPassword = md.digest();
            return bytesToHex(hashedPassword);
        } catch (NoSuchAlgorithmException e) {
            throw new SystemException("SHA-512 algorithm not found", e);
        }
    }
}
