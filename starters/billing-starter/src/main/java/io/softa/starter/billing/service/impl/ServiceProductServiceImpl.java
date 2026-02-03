package io.softa.starter.billing.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.billing.entity.ServiceProduct;
import io.softa.starter.billing.service.ServiceProductService;

/**
 * ServiceProduct Model Service Implementation
 */
@Service
public class ServiceProductServiceImpl extends EntityServiceImpl<ServiceProduct, String> implements ServiceProductService {

}