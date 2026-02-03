package io.softa.starter.metadata.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SysLanguage;
import io.softa.starter.metadata.service.SysLanguageService;

/**
 * SysLanguage Model Service Implementation
 */
@Service
public class SysLanguageServiceImpl extends EntityServiceImpl<SysLanguage, String> implements SysLanguageService {

}