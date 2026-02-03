package io.softa.app.demo.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.app.demo.entity.DeptInfo;
import io.softa.app.demo.service.DeptInfoService;

/**
 * DeptInfo Model Service Implementation
 */
@Service
public class DeptInfoServiceImpl extends EntityServiceImpl<DeptInfo, Long> implements DeptInfoService {

}