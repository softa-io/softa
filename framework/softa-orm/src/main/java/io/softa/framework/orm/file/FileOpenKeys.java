package io.softa.framework.orm.file;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.softa.framework.base.security.HashUtils;

/**
 * Signs and checks file keys — what {@code FileInfo.url} carries instead of a storage link.
 *
 * <p>A key is {@code <fileId>.<exp>.<signature>}: the file, the second it stops opening (epoch
 * seconds), and the first 16 bytes of an HMAC-SHA256 over them, base64url without padding. Nothing is
 * stored and nothing is encrypted — the file id is in every FileInfo already. The signature proves we
 * issued the key, nobody changed it, and, for a user key, that it was issued to the account asking.
 *
 * <p>Two kinds, told apart inside the signature ({@code u|} / {@code p|}) so neither opens as the other:
 * <ul>
 *   <li><b>User key</b>, for a request with a logged-in user. The account ({@code Context.userId}) is
 *   signed in but not written out: checking it means recomputing the signature with the userId of the
 *   session that presents the key, so it opens for that account only, at {@link #USER_PATH}. It lives
 *   until UTC midnight of the issue day plus {@link #USER_KEY_DAYS} days — 30 to 31 days, and the same
 *   key all day long, so a page that rereads its data does not get new image addresses.</li>
 *   <li><b>Unbound key</b>, for everything else: a public page, a scheduled job, a flow nobody
 *   triggered. Anyone holding it can open it, at {@link #UNBOUND_PATH}, for
 *   {@link #UNBOUND_KEY_SECONDS} seconds — as long as a public page's verified session.</li>
 * </ul>
 *
 * <p>Opening a key does not ask whether the caller may still read the record the file hangs on: a
 * key is only handed out by a read that was authorized already. A revoked permission or a removed
 * attachment therefore stops a key already handed out within its lifetime, not at once.
 */
@Component
public class FileOpenKeys {

    /** Where a user key opens, under the context path. Needs the session of the account it was issued to. */
    public static final String USER_PATH = "/file/open/";

    /** Where an unbound key opens, under the context path. Public. */
    public static final String UNBOUND_PATH = "/openapi/file/";

    /** Query parameter asking the open endpoints for a download rather than inline display. */
    public static final String DOWNLOAD_PARAM = "download";

    /** A user key opens until UTC midnight of the day it was issued plus this many days. */
    static final int USER_KEY_DAYS = 31;

    /** An unbound key opens for this many seconds after it was issued. */
    static final long UNBOUND_KEY_SECONDS = 3600;

    private static final int SIGNATURE_BYTES = 16;
    /** Base64url of {@link #SIGNATURE_BYTES} bytes, without padding. */
    private static final int SIGNATURE_CHARS = 22;
    /** Long.MAX_VALUE has 19 digits. */
    private static final int MAX_NUMBER_DIGITS = 19;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final String contextPath;
    private final Clock clock;

    @Autowired
    public FileOpenKeys(FileOpenKeyProperties properties,
                        @Value("${server.servlet.context-path:}") String contextPath) {
        this(properties.getSecret(), contextPath, Clock.systemUTC());
    }

    FileOpenKeys(String secret, String contextPath, Clock clock) {
        if (StringUtils.isBlank(secret)) {
            throw new IllegalStateException("security.file-key.secret must be set.");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.contextPath = StringUtils.removeEnd(Objects.toString(contextPath, "").trim(), "/");
        this.clock = clock;
    }

    /**
     * The address of a key only {@code userId} can open, e.g. {@code /api/hcm/file/open/<key>}.
     *
     * @param fileId   the file
     * @param userId   the account that will open it: the logged-in user of the request being answered
     * @param download true for a download, false to display the file inline
     * @return the address, relative to the site, with the context path
     */
    public String userUrl(Long fileId, Long userId, boolean download) {
        return url(USER_PATH, userKey(fileId, userId), download);
    }

    /**
     * The address of a key anyone can open for an hour, e.g. {@code /api/hcm/openapi/file/<key>}.
     *
     * @param fileId   the file
     * @param download true for a download, false to display the file inline
     * @return the address, relative to the site, with the context path
     */
    public String unboundUrl(Long fileId, boolean download) {
        return url(UNBOUND_PATH, unboundKey(fileId), download);
    }

    /** A key {@code userId} can open until UTC midnight of today plus {@link #USER_KEY_DAYS} days. */
    String userKey(Long fileId, Long userId) {
        if (fileId == null || userId == null) {
            throw new IllegalArgumentException("A user key needs a fileId and a userId.");
        }
        long exp = LocalDate.now(clock.withZone(ZoneOffset.UTC)).plusDays(USER_KEY_DAYS)
                .atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        return fileId + "." + exp + "." + ENCODER.encodeToString(sign(userMessage(fileId, exp, userId)));
    }

    /** A key anyone can open for {@link #UNBOUND_KEY_SECONDS} seconds. */
    String unboundKey(Long fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException("An unbound key needs a fileId.");
        }
        long exp = clock.instant().getEpochSecond() + UNBOUND_KEY_SECONDS;
        return fileId + "." + exp + "." + ENCODER.encodeToString(sign(unboundMessage(fileId, exp)));
    }

    /**
     * Check a user key presented by the session of {@code userId}.
     *
     * @param key    the key from the address
     * @param userId the account of the session presenting it
     * @return the outcome, with the file id when the key opens
     */
    public KeyCheck checkUserKey(String key, Long userId) {
        Parsed parsed = parse(key);
        if (parsed == null) {
            return KeyCheck.of(KeyStatus.MALFORMED);
        }
        if (userId == null || !signatureMatches(userMessage(parsed.fileId(), parsed.exp(), userId), parsed.signature())) {
            return KeyCheck.of(KeyStatus.WRONG_SIGNATURE);
        }
        return checkExpiry(parsed);
    }

    /**
     * Check an unbound key.
     *
     * @param key the key from the address
     * @return the outcome, with the file id when the key opens
     */
    public KeyCheck checkUnboundKey(String key) {
        Parsed parsed = parse(key);
        if (parsed == null) {
            return KeyCheck.of(KeyStatus.MALFORMED);
        }
        if (!signatureMatches(unboundMessage(parsed.fileId(), parsed.exp()), parsed.signature())) {
            return KeyCheck.of(KeyStatus.WRONG_SIGNATURE);
        }
        return checkExpiry(parsed);
    }

    private KeyCheck checkExpiry(Parsed parsed) {
        if (clock.instant().getEpochSecond() >= parsed.exp()) {
            return KeyCheck.of(KeyStatus.EXPIRED);
        }
        return new KeyCheck(KeyStatus.VALID, parsed.fileId());
    }

    private String url(String path, String key, boolean download) {
        return contextPath + path + key + (download ? "?" + DOWNLOAD_PARAM + "=1" : "");
    }

    private static String userMessage(long fileId, long exp, long userId) {
        return "u|" + fileId + "|" + exp + "|" + userId;
    }

    private static String unboundMessage(long fileId, long exp) {
        return "p|" + fileId + "|" + exp;
    }

    private byte[] sign(String message) {
        return Arrays.copyOf(HashUtils.hmacSha256(secret, message.getBytes(StandardCharsets.UTF_8)), SIGNATURE_BYTES);
    }

    private boolean signatureMatches(String message, byte[] presented) {
        return MessageDigest.isEqual(sign(message), presented);
    }

    /** The three parts of a well-formed key, or null for anything else. */
    private static Parsed parse(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split("\\.", -1);
        if (parts.length != 3 || !isNumber(parts[0]) || !isNumber(parts[1]) || parts[2].length() != SIGNATURE_CHARS) {
            return null;
        }
        try {
            return new Parsed(Long.parseLong(parts[0]), Long.parseLong(parts[1]), DECODER.decode(parts[2]));
        } catch (IllegalArgumentException e) {
            // NumberFormatException included: a 19-digit number above Long.MAX_VALUE, or a bad signature.
            return null;
        }
    }

    /** ASCII digits only — {@code Long.parseLong} would also take other scripts' digits. */
    private static boolean isNumber(String text) {
        if (text.isEmpty() || text.length() > MAX_NUMBER_DIGITS) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private record Parsed(long fileId, long exp, byte[] signature) {
    }

    /** How a key fared. */
    public enum KeyStatus {
        /** Signed by us, for this account where it matters, and not expired. */
        VALID,
        /** Not a key at all. */
        MALFORMED,
        /** Altered, issued to another account, or a key of the other kind. */
        WRONG_SIGNATURE,
        /** Genuine, but past its expiry. */
        EXPIRED
    }

    /**
     * The outcome of checking a key.
     *
     * @param status how the key fared
     * @param fileId the file it opens; null unless {@code status} is {@link KeyStatus#VALID}
     */
    public record KeyCheck(KeyStatus status, Long fileId) {

        static KeyCheck of(KeyStatus status) {
            return new KeyCheck(status, null);
        }

        public boolean valid() {
            return status == KeyStatus.VALID;
        }
    }
}
