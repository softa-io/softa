package io.softa.starter.billing.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.starter.billing.entity.ServiceRecord;
import io.softa.starter.billing.service.ServiceRecordService;
import io.softa.framework.web.controller.EntityController;

/**
 * ServiceRecord Model Controller
 */
@Tag(name = "ServiceRecord")
@RestController
@RequestMapping("/ServiceRecord")
public class ServiceRecordController extends EntityController<ServiceRecordService, ServiceRecord, String> {

}