package io.softa.starter.message.mail.smtp;

import java.util.Map;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import io.softa.starter.message.shared.ErrorCategory;
import io.softa.starter.message.shared.ErrorClassifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The error code a failed SMTP send leaves on its record. Spring wraps JavaMail's exceptions, so the
 * server's reply code is dug out of the failed messages and cause chains: a refused recipient used to
 * read as a retryable {@code SMTP_MailSendException} and a failed login's
 * {@code SMTP_MailAuthenticationException} as unknown, and both were retried until they were
 * dead-lettered.
 */
class SmtpFailureCodeTest {

    private final ErrorClassifier classifier = new ErrorClassifier();

    @Test
    void aRefusedRecipientIsCodedWithTheServersReply_andFailsAtOnce() throws Exception {
        SendFailedException invalid = new SendFailedException("Invalid Addresses", new SMTPAddressFailedException(
                new InternetAddress("nobody@example.com"), "RCPT TO:<nobody@example.com>", 550, "550 5.1.1 User unknown"));
        MailSendException wrapped = new MailSendException(Map.of(new Object(), invalid));

        String code = SmtpMailTransport.classify(wrapped);

        assertEquals("SMTP_550", code);
        assertEquals(ErrorCategory.PERMANENT, classifier.classify(code, wrapped.getMessage()));
    }

    @Test
    void aRefusalAfterTheMessageIsCodedWithItsReply() {
        SMTPSendFailedException rejected = new SMTPSendFailedException("DATA", 554, "554 5.7.1 Message rejected",
                null, null, null, null);

        assertEquals("SMTP_554", SmtpMailTransport.classify(new MailSendException("Failed messages", rejected)));
    }

    @Test
    void aTemporaryRefusalStaysRetryable() throws Exception {
        SendFailedException busy = new SendFailedException("Try later", new SMTPAddressFailedException(
                new InternetAddress("ada@example.com"), "RCPT TO:<ada@example.com>", 451, "451 4.7.1 Greylisted"));

        String code = SmtpMailTransport.classify(new MailSendException(Map.of(new Object(), busy)));

        assertEquals("SMTP_451", code);
        assertEquals(ErrorCategory.TRANSIENT, classifier.classify(code, null));
    }

    @Test
    void aFailedLoginIsCodedAsAnAuthenticationFailure() {
        MailAuthenticationException failed = new MailAuthenticationException("Authentication failed",
                new AuthenticationFailedException("535 5.7.8 Username and Password not accepted"));

        String code = SmtpMailTransport.classify(failed);

        assertEquals("SMTP_MailAuthenticationException", code);
        assertEquals(ErrorCategory.AUTH, classifier.classify(code, failed.getMessage()));
    }

    @Test
    void aFailureWithNoReplyKeepsItsType() {
        // A connection that never got as far as a reply: retried, as before.
        String code = SmtpMailTransport.classify(new MailSendException("Mail server connection failed"));

        assertEquals("SMTP_MailSendException", code);
        assertEquals(ErrorCategory.TRANSIENT, classifier.classify(code, null));
    }
}
