package io.softa.app.demo.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.app.demo.entity.ProjectInfo;
import io.softa.app.demo.service.ProjectInfoService;

/**
 * ProjectInfo Model Service Implementation
 */
@Service
public class ProjectInfoServiceImpl extends EntityServiceImpl<ProjectInfo, Long> implements ProjectInfoService {

}