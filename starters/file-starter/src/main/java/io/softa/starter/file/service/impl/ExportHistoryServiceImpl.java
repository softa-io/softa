package io.softa.starter.file.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.file.entity.ExportHistory;
import io.softa.starter.file.service.ExportHistoryService;

/**
 * ExportHistory Service Implementation
 */
@Service
public class ExportHistoryServiceImpl extends EntityServiceImpl<ExportHistory, String> implements ExportHistoryService {

}