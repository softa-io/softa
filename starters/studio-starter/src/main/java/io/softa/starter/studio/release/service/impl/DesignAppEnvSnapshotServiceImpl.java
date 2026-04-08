package io.softa.starter.studio.release.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.release.entity.DesignAppEnvSnapshot;
import io.softa.starter.studio.release.service.DesignAppEnvSnapshotService;

/**
 * DesignAppEnvSnapshot Model Service Implementation
 */
@Service
public class DesignAppEnvSnapshotServiceImpl extends EntityServiceImpl<DesignAppEnvSnapshot, Long>
        implements DesignAppEnvSnapshotService {

}

