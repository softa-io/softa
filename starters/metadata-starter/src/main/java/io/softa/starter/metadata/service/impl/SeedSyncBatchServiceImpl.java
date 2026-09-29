package io.softa.starter.metadata.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SeedSyncBatch;
import io.softa.starter.metadata.service.SeedSyncBatchService;

/**
 * SeedSyncBatch Model Service Implementation
 */
@Service
public class SeedSyncBatchServiceImpl extends EntityServiceImpl<SeedSyncBatch, Long> implements SeedSyncBatchService {
}
