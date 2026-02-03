package io.softa.starter.cron.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.cron.entity.SysCronLog;
import io.softa.starter.cron.service.SysCronLogService;

/**
 * SysCronLog Model Service Implementation
 */
@Service
public class SysCronLogServiceImpl extends EntityServiceImpl<SysCronLog, Long> implements SysCronLogService {

}