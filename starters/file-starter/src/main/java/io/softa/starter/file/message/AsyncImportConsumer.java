package io.softa.starter.file.message;

import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.service.FileService;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.entity.ImportHistory;
import io.softa.starter.file.service.ImportHistoryService;
import io.softa.starter.file.service.ImportService;

/**
 * AsyncImportConsumer
 */
@Slf4j
@Component
public class AsyncImportConsumer {

    @Autowired
    private ImportService importService;

    @Autowired
    private FileService fileService;

    @Autowired
    private ImportHistoryService importHistoryService;

    @PulsarListener(topics = "${mq.topics.async-import.topic}", subscriptionName = "${mq.topics.async-import.sub}")
    public void onMessage(ImportTemplateDTO importTemplateDTO) {
        ImportHistory importHistory = importHistoryService.getById(importTemplateDTO.getHistoryId())
                .orElseThrow(() -> new IllegalArgumentException("The import history with ID `{0}` does not exist", importTemplateDTO.getHistoryId()));
        InputStream inputStream = fileService.downloadStream(importTemplateDTO.getFileId());
        importService.syncImport(importTemplateDTO, inputStream, importHistory);
    }

}
