package io.softa.starter.message.mail.smtp;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.service.FileService;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.enums.MailPriority;
import io.softa.starter.message.mail.enums.SendProtocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wire-level tests for {@link SmtpMailTransport} against an embedded GreenMail
 * SMTP server. Unlike {@code MailMessageHandlerTest} (which mocks the transport
 * to verify orchestration), these exercise the real path: {@code MimeMessage}
 * assembly, SMTP {@code AUTH}, multi-recipient delivery, priority / Reply-To
 * headers, attachment streaming, and success/failure result parsing.
 */
class SmtpMailTransportTest {

    private static final String USER = "noreply@example.com";
    private static final String PASS = "secret";

    private GreenMail greenMail;
    private SmtpMailTransport transport;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetup.SMTP.dynamicPort());
        greenMail.start();
        greenMail.setUser(USER, USER, PASS);

        transport = new SmtpMailTransport();
        ReflectionTestUtils.setField(transport, "messageProperties", new MessageProperties());
    }

    @AfterEach
    void tearDown() {
        greenMail.stop();
    }

    private MailSendServerConfig config() {
        MailSendServerConfig c = new MailSendServerConfig();
        c.setProtocol(SendProtocol.SMTP);
        c.setHost("127.0.0.1");
        c.setPort(greenMail.getSmtp().getPort());
        c.setUsername(USER);
        c.setPassword(PASS);
        return c;
    }

    private SmtpMailRequest request(String subject, String html, String text) {
        SmtpMailRequest r = new SmtpMailRequest();
        r.setFromAddress(USER);
        r.setFromName("Softa Ops");
        r.setTo(List.of("alice@example.com"));
        r.setSubject(subject);
        r.setHtmlBody(html);
        r.setTextBody(text);
        return r;
    }

    @Test
    void sendsMessageThroughSmtpAndReturnsMessageId() throws Exception {
        SmtpSendResult result = transport.send(config(), request("Welcome", "<p>Hello Alice</p>", null));

        assertTrue(result.isSuccess());
        assertNotNull(result.getMessageId());

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(1, received.length);
        assertEquals("Welcome", received[0].getSubject());
        assertTrue(received[0].getFrom()[0].toString().contains(USER));
        assertTrue(GreenMailUtil.getBody(received[0]).contains("Hello Alice"));
    }

    @Test
    void deliversToAllRecipients_toAndCc() throws Exception {
        SmtpMailRequest r = request("Notice", "<p>Hi</p>", null);
        r.setTo(List.of("alice@example.com"));
        r.setCc(List.of("bob@example.com"));

        SmtpSendResult result = transport.send(config(), r);

        assertTrue(result.isSuccess());
        // GreenMail delivers one copy per RCPT TO (to + cc = 2).
        assertTrue(greenMail.waitForIncomingEmail(5000, 2));
        assertEquals(2, greenMail.getReceivedMessages().length);
        MimeMessage msg = greenMail.getReceivedMessages()[0];
        assertTrue(msg.getHeader("To")[0].contains("alice@example.com"));
        assertTrue(msg.getHeader("Cc")[0].contains("bob@example.com"));
    }

    @Test
    void setsPriorityAndReplyToHeaders() throws Exception {
        SmtpMailRequest r = request("Urgent", "<p>!</p>", null);
        r.setPriority(MailPriority.HIGH);
        r.setReplyTo("reply@example.com");

        transport.send(config(), r);

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage msg = greenMail.getReceivedMessages()[0];
        assertEquals("1", msg.getHeader("X-Priority")[0]);
        assertEquals("High", msg.getHeader("Importance")[0]);
        assertTrue(msg.getHeader("Reply-To")[0].contains("reply@example.com"));
    }

    @Test
    void htmlAndTextProduceMultipartAlternative() throws Exception {
        SmtpSendResult result = transport.send(config(), request("Alt", "<p>rich body</p>", "plain fallback"));

        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage msg = greenMail.getReceivedMessages()[0];
        assertTrue(msg.getContentType().toLowerCase().contains("multipart"));
        String body = GreenMailUtil.getBody(msg);
        assertTrue(body.contains("rich body"));
        assertTrue(body.contains("plain fallback"));
    }

    @Test
    void sendWithAttachment_streamsFileAsMimePart() throws Exception {
        FileService fileService = mock(FileService.class);
        when(fileService.downloadStream(99L))
                .thenReturn(new ByteArrayInputStream("PDF-BYTES".getBytes(StandardCharsets.UTF_8)));
        ReflectionTestUtils.setField(transport, "fileService", fileService);

        FileInfo attachment = new FileInfo();
        attachment.setFileId(99L);
        attachment.setFileName("report.pdf");

        SmtpMailRequest r = request("With file", "<p>see attached</p>", null);
        r.setAttachments(List.of(attachment));

        SmtpSendResult result = transport.send(config(), r);

        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage msg = greenMail.getReceivedMessages()[0];
        assertTrue(msg.getContentType().toLowerCase().contains("multipart"));
        assertTrue(hasAttachment(msg, "report.pdf"), "message should carry the report.pdf attachment part");
    }

    @Test
    void unreachableServer_returnsFailureResult() {
        MailSendServerConfig badConfig = config();
        badConfig.setPort(1);  // nothing listens on port 1 → connection refused

        SmtpSendResult result = transport.send(badConfig, request("Nope", "<p>x</p>", null));

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorCode());
        assertTrue(result.getErrorCode().startsWith("SMTP_"));
    }

    private static boolean hasAttachment(MimeMessage msg, String fileName) throws Exception {
        MimeMultipart mp = (MimeMultipart) msg.getContent();
        for (int i = 0; i < mp.getCount(); i++) {
            if (fileName.equals(mp.getBodyPart(i).getFileName())) {
                return true;
            }
        }
        return false;
    }
}
