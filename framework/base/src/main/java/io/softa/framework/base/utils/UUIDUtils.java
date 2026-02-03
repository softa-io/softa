package io.softa.framework.base.utils;

import java.util.UUID;

/**
 * UUIDUtils
 *  1. Generate UUID string without dashes
 *  2. Generate UUID string with 22 characters in Base62
 */
public class UUIDUtils {
    private static final char[] BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    /**
     * Generate UUID string, remove `-`.
     * @return UUID string without dashes, 32 characters
     */
    public static String uuidWithoutDash() {
        UUID uuid = UUID.randomUUID();
        return uuid.toString().replace("-", "");
    }

    /**
     * Generate UUID string in Base62.
     * @return UUID string in Base62Base62, 22 characters
     */
    public static String shortUUID22() {
        UUID uuid = UUID.randomUUID();
        long mostSigBits = uuid.getMostSignificantBits();
        long leastSigBits = uuid.getLeastSignificantBits();
        return longToBase62(mostSigBits) + longToBase62(leastSigBits);
    }

    /**
     * Convert long to Base62 string
     * @param value long value
     * @return Base62 string
     */
    private static String longToBase62(long value) {
        StringBuilder sb = new StringBuilder();
        do {
            int digit = (int)(value % 62);
            // Convert negative number to positive number
            digit = Math.abs(digit);
            sb.append(BASE62[digit]);
            value /= 62;
        } while (value != 0);
        return sb.toString();
    }
}
