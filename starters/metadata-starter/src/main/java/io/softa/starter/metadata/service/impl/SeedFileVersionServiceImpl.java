package io.softa.starter.metadata.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SeedFileVersion;
import io.softa.starter.metadata.service.SeedFileVersionService;

/**
 * SeedFileVersion Model Service Implementation
 */
@Service
public class SeedFileVersionServiceImpl extends EntityServiceImpl<SeedFileVersion, Long> implements SeedFileVersionService {
}
