package io.softa.app.demo.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.app.demo.entity.EmpInfo;
import io.softa.app.demo.service.EmpInfoService;
import io.softa.framework.web.controller.EntityController;

/**
 * EmpInfo Model Controller
 */
@Tag(name = "EmpInfo")
@RestController
@RequestMapping("/EmpInfo")
public class EmpInfoController extends EntityController<EmpInfoService, EmpInfo, Long> {

}