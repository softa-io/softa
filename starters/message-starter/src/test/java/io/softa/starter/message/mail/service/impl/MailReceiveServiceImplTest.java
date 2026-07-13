package io.softa.starter.message.mail.service.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.entity.MailReceiveRecord;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.enums.MailReceiveStatus;
import io.softa.starter.message.mail.enums.ReceivedMailType;
import io.softa.starter.message.mail.service.MailReceiveRecordService;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.support.BounceReceiptLinker;
import io.softa.starter.message.mail.support.MailClassification;
import io.softa.starter.message.mail.support.MailClassifier;
import io.softa.starter.message.mail.support.MailMessageParser;
import io.softa.starter.message.mail.support.MailServerDispatcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Orchestration-level tests for {@link MailReceiveServiceImpl}: folder parsing,
 * read-state updates, dedup, and the persist gate in {@code processOne}. MIME
 * parsing and bounce/receipt linkage are covered by {@code MailMessageParserTest}
 * and {@code BounceReceiptLinkerTest}; here the collaborators are wired as real
 * instances so {@code processOne} exercises the full path end to end.
 */
class MailReceiveServiceImplTest {

    private MailReceiveServiceImpl service;
    private MailReceiveRecordService recordService;
    private MessageProperties messageProperties;

    @BeforeEach
    void setUp() {
        service = new MailReceiveServiceImpl();
        recordService = mock(MailReceiveRecordService.class);
        MailServerDispatcher dispatcher = mock(MailServerDispatcher.class);
        messageProperties = new MessageProperties();

        // Real collaborators wired from mocks so processOne runs end-to-end.
        MailClassifier mailClassifier = mock(MailClassifier.class);
        MailClassification normal = new MailClassification();
        normal.setType(ReceivedMailType.NORMAL);
        when(mailClassifier.classify(any())).thenReturn(normal);
        MailMessageParser parser = new MailMessageParser();
        ReflectionTestUtils.setField(parser, "mailClassifier", mailClassifier);
        ReflectionTestUtils.setField(parser, "messageProperties", messageProperties);
        BounceReceiptLinker linker = new BounceReceiptLinker();
        ReflectionTestUtils.setField(linker, "sendRecordService", mock(MailSendRecordService.class));

        ReflectionTestUtils.setField(service, "dispatcher", dispatcher);
        ReflectionTestUtils.setField(service, "recordService", recordService);
        ReflectionTestUtils.setField(service, "mailMessageParser", parser);
        ReflectionTestUtils.setField(service, "bounceReceiptLinker", linker);
        ReflectionTestUtils.setField(service, "messageProperties", messageProperties);
    }

    // ========== parseFolders Tests ==========

    @Test
    void parseFoldersUseFetchFoldersWhenSet() {
        MailReceiveServerConfig config = new MailReceiveServerConfig();
        config.setFetchFolders("INBOX, Junk, Trash");

        List<String> folders = invokeParseFolders(config);
        Assertions.assertEquals(3, folders.size());
        Assertions.assertEquals("INBOX", folders.get(0));
        Assertions.assertEquals("Junk", folders.get(1));
        Assertions.assertEquals("Trash", folders.get(2));
    }

    @Test
    void parseFoldersDefaultsToINBOX() {
        MailReceiveServerConfig config = new MailReceiveServerConfig();
        config.setFetchFolders(null);

        List<String> folders = invokeParseFolders(config);
        Assertions.assertEquals(1, folders.size());
        Assertions.assertEquals("INBOX", folders.getFirst());
    }

    @Test
    void parseFoldersTrimsWhitespace() {
        MailReceiveServerConfig config = new MailReceiveServerConfig();
        config.setFetchFolders("  INBOX ,  Junk  ");

        List<String> folders = invokeParseFolders(config);
        Assertions.assertEquals(2, folders.size());
        Assertions.assertEquals("INBOX", folders.get(0));
        Assertions.assertEquals("Junk", folders.get(1));
    }

    @Test
    void parseFoldersSkipsEmptyEntries() {
        MailReceiveServerConfig config = new MailReceiveServerConfig();
        config.setFetchFolders("INBOX,,Junk,");

        List<String> folders = invokeParseFolders(config);
        Assertions.assertEquals(2, folders.size());
        Assertions.assertEquals("INBOX", folders.get(0));
        Assertions.assertEquals("Junk", folders.get(1));
    }

    @Test
    void parseFoldersBlankStringFallsBackToDefault() {
        MailReceiveServerConfig config = new MailReceiveServerConfig();
        config.setFetchFolders("   ");

        List<String> folders = invokeParseFolders(config);
        Assertions.assertEquals(1, folders.size());
        Assertions.assertEquals("INBOX", folders.getFirst());
    }

    // ========== markAsRead Tests ==========

    @Test
    void markAsReadSingleUpdatesStatus() {
        MailReceiveRecord record = new MailReceiveRecord();
        record.setId(1L);
        record.setStatus(MailReceiveStatus.UNREAD);
        when(recordService.getById(1L)).thenReturn(Optional.of(record));

        service.markAsRead(1L);

        Assertions.assertEquals(MailReceiveStatus.READ, record.getStatus());
        verify(recordService).updateOne(record);
    }

    @Test
    void markAsReadSingleThrowsForNonexistent() {
        when(recordService.getById(99L)).thenReturn(Optional.empty());

        Assertions.assertThrows(Exception.class, () -> service.markAsRead(99L));
    }

    @Test
    void markAsReadBatchCallsUpdateByFilter() {
        List<Long> ids = List.of(1L, 2L, 3L);

        service.markAsRead(ids);

        verify(recordService).updateByFilter(any(), any());
    }

    // ========== isDuplicate Tests ==========

    @Test
    void isDuplicateReturnsFalseForNullMessageId() {
        Assertions.assertFalse(invokeIsDuplicate(null, 1L));
    }

    @Test
    void isDuplicateReturnsFalseForBlankMessageId() {
        Assertions.assertFalse(invokeIsDuplicate("", 1L));
    }

    @Test
    void isDuplicateReturnsTrueWhenRecordExists() {
        when(recordService.count(any())).thenReturn(1L);
        Assertions.assertTrue(invokeIsDuplicate("<msg@example.com>", 1L));
    }

    @Test
    void isDuplicateReturnsFalseWhenNoRecordExists() {
        when(recordService.count(any())).thenReturn(0L);
        Assertions.assertFalse(invokeIsDuplicate("<msg@example.com>", 1L));
    }

    // ========== processOne orchestration ==========

    @Test
    void processOneSkipsDuplicate() throws Exception {
        MailReceiveServerConfig config = new MailReceiveServerConfig();
        config.setId(8L);
        MimeMessage msg = new MimeMessage(session());
        msg.setText("body", "UTF-8");
        msg.setHeader("Message-ID", "<dup@example.com>");
        msg.saveChanges();
        when(recordService.count(any())).thenReturn(1L);   // already seen

        MailReceiveRecord record = invokeProcessOne(msg, config, "INBOX");
        Assertions.assertNull(record);
        verify(recordService, never()).createOne(any());
    }

    @Test
    void processOnePersistsNonDuplicate() throws Exception {
        MailReceiveServerConfig config = new MailReceiveServerConfig();
        config.setId(9L);
        MimeMessage msg = new MimeMessage(session());
        msg.setText("body", "UTF-8");
        msg.setHeader("Message-ID", "<new@example.com>");
        msg.saveChanges();
        when(recordService.count(any())).thenReturn(0L);

        MailReceiveRecord record = invokeProcessOne(msg, config, "INBOX");
        Assertions.assertNotNull(record);
        Assertions.assertNull(record.getEmlFileId());   // archiveEml disabled by default
        verify(recordService).createOne(record);
    }

    // ========== Helpers ==========

    @SuppressWarnings("unchecked")
    private List<String> invokeParseFolders(MailReceiveServerConfig config) {
        return (List<String>) ReflectionTestUtils.invokeMethod(service, "parseFolders", config);
    }

    private boolean invokeIsDuplicate(String messageId, Long serverConfigId) {
        return (boolean) ReflectionTestUtils.invokeMethod(service, "isDuplicate", messageId, serverConfigId);
    }

    private MailReceiveRecord invokeProcessOne(
            Message message, MailReceiveServerConfig config, String folderName) throws Exception {
        Method m = MailReceiveServiceImpl.class.getDeclaredMethod(
                "processOne", Message.class, MailReceiveServerConfig.class, String.class);
        m.setAccessible(true);
        try {
            return (MailReceiveRecord) m.invoke(service, message, config, folderName);
        } catch (InvocationTargetException ite) {
            if (ite.getCause() instanceof Exception cause) throw cause;
            throw ite;
        }
    }

    private static Session session() {
        return Session.getInstance(new Properties());
    }
}
