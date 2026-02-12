package io.softa.starter.file.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.file.entity.ImportHistory;
import io.softa.starter.file.service.ImportHistoryService;

/**
 * ImportHistory service implementation
 */
@Service
public class ImportHistoryServiceImpl extends EntityServiceImpl<ImportHistory, Long> implements ImportHistoryService {

}