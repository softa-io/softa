package io.softa.starter.message.mail.classifier;

import java.util.Locale;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.support.MailClassification;

/**
 * Sets {@code encrypted = true} when the message body is wrapped in a
 * standard encrypted MIME envelope:
 * <ul>
 *   <li>{@code multipart/encrypted; protocol="application/pgp-encrypted"} —
 *       PGP-MIME (RFC 3156).</li>
 *   <li>{@code application/pkcs7-mime} or {@code application/x-pkcs7-mime}
 *       with {@code smime-type=enveloped-data} — S/MIME (RFC 8551).</li>
 * </ul>
 * <p>
 * Encryption is a payload wrapper, not a content category — an encrypted
 * email can still be a bounce, a calendar invite, regular correspondence,
 * etc. The flag tells the inbox UI that the body is opaque without a key,
 * so it can render an "encrypted" badge instead of dumping base64.
 */
@Component
public class EncryptedFlagDetector implements MailFlagDetector {

    @Override
    public void apply(MimeMessage message, MailClassification classification) throws Exception {
        String contentType = message.getContentType();
        if (contentType == null) return;
        String ct = contentType.toLowerCase(Locale.ROOT);

        // PGP-MIME
        if (ct.contains("multipart/encrypted")) {
            classification.setEncrypted(true);
            return;
        }

        // S/MIME — pkcs7-mime is used for both signed-only (smime-type=signed-data)
        // and encrypted (smime-type=enveloped-data). Be conservative: only flag
        // when smime-type explicitly indicates encryption, OR when the smime-type
        // parameter is absent (older clients omit it for enveloped-data).
        boolean isPkcs7 = ct.contains("application/pkcs7-mime")
                || ct.contains("application/x-pkcs7-mime");
        if (isPkcs7) {
            if (ct.contains("smime-type=enveloped-data")
                    || ct.contains("smime-type=authenticated-enveloped-data")
                    || !ct.contains("smime-type=signed-data")) {
                classification.setEncrypted(true);
            }
        }
    }
}
