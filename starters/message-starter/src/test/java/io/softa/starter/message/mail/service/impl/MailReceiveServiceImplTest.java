package io.softa.starter.message.mail.service.impl;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.message.mail.entity.MailReceiveRecord;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.enums.MailReceiveStatus;
import io.softa.starter.message.mail.enums.ReceivedMailType;
import io.softa.starter.message.mail.service.MailReceiveRecordService;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.support.MailClassifier;
import io.softa.starter.message.mail.support.MailServerDispatcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MailReceiveServiceImplTest {

    private MailReceiveServiceImpl service;
    private MailReceiveRecordService recordService;

    @BeforeEach
    void setUp() {
        service = new MailReceiveServiceImpl();
        recordService = mock(MailReceiveRecordService.class);
        MailSendRecordService sendRecordService = mock(MailSendRecordService.class);
        MailServerDispatcher dispatcher = mock(MailServerDispatcher.class);
        MailClassifier mailClassifier = mock(MailClassifier.class);

        ReflectionTestUtils.setField(service, "dispatcher", dispatcher);
        ReflectionTestUtils.setField(service, "recordService", recordService);
        ReflectionTestUtils.setField(service, "sendRecordService", sendRecordService);
        ReflectionTestUtils.setField(service, "mailClassifier", mailClassifier);
        // fileService is null by default (optional dependency)
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
    void parseFoldersFallsBackToInboxFolder() {
        MailReceiveServerConfig config = new MailReceiveServerConfig();
        config.setFetchFolders(null);
        config.setInboxFolder("CustomInbox");

        List<String> folders = invokeParseFolders(config);
        Assertions.assertEquals(1, folders.size());
        Assertions.assertEquals("CustomInbox", folders.getFirst());
    }

    @Test
    void parseFoldersDefaultsToINBOX() {
        MailReceiveServerConfig config = new MailReceiveServerConfig();
        config.setFetchFolders(null);
        config.setInboxFolder(null);

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
        config.setInboxFolder(null);

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

    // ========== processClassification Tests ==========

    @Test
    void processClassificationSkipsNormalMail() {
        MailReceiveRecord record = new MailReceiveRecord();
        record.setMailType(ReceivedMailType.NORMAL.getCode());
        record.setOriginalMessageId(null);

        // Should not invoke sendRecordService at all
        invokeProcessClassification(record);
        // No exception expected; sendRecordService.findByMessageId not called
    }

    @Test
    void processClassificationSkipsWhenNoOriginalMessageId() {
        MailReceiveRecord record = new MailReceiveRecord();
        record.setMailType(ReceivedMailType.BOUNCE.getCode());
        record.setOriginalMessageId(null); // null means cannot link

        invokeProcessClassification(record);
        // Should not try to find send record
    }

    // ========== isDuplicate Tests ==========

    @Test
    void isDuplicateReturnsFalseForNullMessageId() {
        boolean result = invokeIsDuplicate(null, 1L);
        Assertions.assertFalse(result);
    }

    @Test
    void isDuplicateReturnsFalseForBlankMessageId() {
        boolean result = invokeIsDuplicate("", 1L);
        Assertions.assertFalse(result);
    }

    @Test
    void isDuplicateReturnsTrueWhenRecordExists() {
        when(recordService.count(any())).thenReturn(1L);
        boolean result = invokeIsDuplicate("<msg@example.com>", 1L);
        Assertions.assertTrue(result);
    }

    @Test
    void isDuplicateReturnsFalseWhenNoRecordExists() {
        when(recordService.count(any())).thenReturn(0L);
        boolean result = invokeIsDuplicate("<msg@example.com>", 1L);
        Assertions.assertFalse(result);
    }

    // ========== Helper: invoke private methods via ReflectionTestUtils ==========

    @SuppressWarnings("unchecked")
    private List<String> invokeParseFolders(MailReceiveServerConfig config) {
        return (List<String>) ReflectionTestUtils.invokeMethod(service, "parseFolders", config);
    }

    private void invokeProcessClassification(MailReceiveRecord record) {
        ReflectionTestUtils.invokeMethod(service, "processClassification", record);
    }

    private boolean invokeIsDuplicate(String messageId, Long serverConfigId) {
        return (boolean) ReflectionTestUtils.invokeMethod(service, "isDuplicate", messageId, serverConfigId);
    }
}
