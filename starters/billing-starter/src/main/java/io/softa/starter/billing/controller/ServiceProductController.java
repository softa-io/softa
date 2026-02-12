package io.softa.starter.billing.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.billing.entity.ServiceProduct;
import io.softa.starter.billing.service.ServiceProductService;

/**
 * ServiceProduct Model Controller
 */
@Tag(name = "ServiceProduct")
@RestController
@RequestMapping("/ServiceProduct")
public class ServiceProductController extends EntityController<ServiceProductService, ServiceProduct, Long> {

}