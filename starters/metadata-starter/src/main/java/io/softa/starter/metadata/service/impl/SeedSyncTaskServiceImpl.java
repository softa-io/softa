package io.softa.starter.metadata.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SeedSyncTask;
import io.softa.starter.metadata.service.SeedSyncTaskService;

/**
 * SeedSyncTask Model Service Implementation
 */
@Service
public class SeedSyncTaskServiceImpl extends EntityServiceImpl<SeedSyncTask, Long> implements SeedSyncTaskService {
}
