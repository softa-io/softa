package io.softa.app.demo.service.impl;

import org.springframework.stereotype.Service;

import io.softa.app.demo.entity.EmpInfo;
import io.softa.app.demo.service.EmpInfoService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;

/**
 * EmpInfo Model Service Implementation
 */
@Service
public class EmpInfoServiceImpl extends EntityServiceImpl<EmpInfo, Long> implements EmpInfoService {

}