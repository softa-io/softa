package io.softa.starter.billing.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.starter.billing.entity.ServiceOrder;
import io.softa.starter.billing.service.ServiceOrderService;
import io.softa.framework.web.controller.EntityController;

/**
 * ServiceOrder Model Controller
 */
@Tag(name = "ServiceOrder")
@RestController
@RequestMapping("/ServiceOrder")
public class ServiceOrderController extends EntityController<ServiceOrderService, ServiceOrder, String> {

}