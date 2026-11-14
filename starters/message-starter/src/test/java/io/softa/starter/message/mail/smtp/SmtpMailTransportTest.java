package io.softa.starter.message.mail.smtp;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
    private MessageProperties properties;
    private SmtpMailTransport transport;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetup.SMTP.dynamicPort());
        greenMail.start();
        greenMail.setUser(USER, USER, PASS);

        properties = new MessageProperties();
        transport = new SmtpMailTransport();
        ReflectionTestUtils.setField(transport, "messageProperties", properties);
    }

    @AfterEach
    void tearDown() {
        transport.closePooledConnections();
        greenMail.stop();
    }

    /** An unsaved config: no id, so it always takes the single-use sender. */
    private MailSendServerConfig config() {
        MailSendServerConfig c = new MailSendServerConfig();
        c.setProtocol(SendProtocol.SMTP);
        c.setHost("127.0.0.1");
        c.setPort(greenMail.getSmtp().getPort());
        c.setUsername(USER);
        c.setPassword(PASS);
        return c;
    }

    /** A saved config: its sends go through the connection pool keyed by its id. */
    private MailSendServerConfig savedConfig() {
        MailSendServerConfig c = config();
        c.setId(1L);
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

    @Test
    void pooledSendsDeliverEveryMessage() throws Exception {
        MailSendServerConfig config = savedConfig();

        for (int i = 1; i <= 3; i++) {
            assertTrue(transport.send(config, request("Pooled " + i, "<p>" + i + "</p>", null)).isSuccess());
        }

        assertTrue(greenMail.waitForIncomingEmail(5000, 3));
    }

    @Test
    void changedPasswordIsNotServedFromThePooledConnection() {
        MailSendServerConfig config = savedConfig();
        assertTrue(transport.send(config, request("Before", "<p>x</p>", null)).isSuccess());

        // The pooled connection is already authenticated; reusing it would let the
        // wrong password through. A changed config must open (and authenticate) anew.
        config.setPassword("rotated-wrong");
        SmtpSendResult result = transport.send(config, request("After", "<p>x</p>", null));

        assertFalse(result.isSuccess());
        assertEquals("SMTP_MailAuthenticationException", result.getErrorCode());
    }

    @Test
    void changedServerIsNotServedFromThePooledConnection() {
        MailSendServerConfig config = savedConfig();
        assertTrue(transport.send(config, request("Before", "<p>x</p>", null)).isSuccess());

        config.setPort(1);  // nothing listens on port 1
        assertFalse(transport.send(config, request("After", "<p>x</p>", null)).isSuccess());
    }

    @Test
    void evictionClosesIdleConnectionsAndSendsStillWork() throws Exception {
        ReflectionTestUtils.setField(transport, "poolIdleTimeout", Duration.ZERO);
        MailSendServerConfig config = savedConfig();
        assertTrue(transport.send(config, request("One", "<p>1</p>", null)).isSuccess());

        transport.evictIdleConnections();

        assertTrue(transport.send(config, request("Two", "<p>2</p>", null)).isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 2));
    }

    @Test
    void aServerWithReuseOffSendsOnAConnectionOfItsOwn() throws Exception {
        MailSendServerConfig config = savedConfig();
        config.setConnectionReuseEnabled(false);

        assertTrue(transport.send(config, request("Own", "<p>x</p>", null)).isSuccess());

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        assertTrue(pools().isEmpty(), "no pool is kept for a server with reuse off");
    }

    @Test
    void turningReuseOffClosesTheServersPool() throws Exception {
        MailSendServerConfig config = savedConfig();
        assertTrue(transport.send(config, request("Pooled", "<p>1</p>", null)).isSuccess());
        assertEquals(1, pools().size());

        config.setConnectionReuseEnabled(false);
        assertTrue(transport.send(config, request("Own", "<p>2</p>", null)).isSuccess());

        assertTrue(greenMail.waitForIncomingEmail(5000, 2));
        assertTrue(pools().isEmpty());
    }

    private Map<?, ?> pools() {
        return (Map<?, ?>) ReflectionTestUtils.getField(transport, "pools");
    }

    @Test
    void anUnsavedConfigSendsWithASingleUseSender() throws Exception {
        // A connectivity test sends through a config with no id yet: nothing to key a pool by.
        assertTrue(transport.send(config(), request("Plain", "<p>x</p>", null)).isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
    }

    @Test
    void smtpsConfigCarriesTimeoutsUnderItsOwnPrefix() {
        MailSendServerConfig config = config();
        config.setProtocol(SendProtocol.SMTPS);

        Properties props = transport.buildSender(config).getJavaMailProperties();

        assertEquals(5000L, props.get("mail.smtps.connectiontimeout"));
        assertEquals(30000L, props.get("mail.smtps.timeout"));
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
