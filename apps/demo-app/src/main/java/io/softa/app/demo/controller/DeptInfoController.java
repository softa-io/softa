package io.softa.app.demo.controller;

import io.softa.app.demo.entity.DeptInfo;
import io.softa.app.demo.service.DeptInfoService;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.annotation.DataMask;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * DeptInfo Model Controller
 */
@Tag(name = "DeptInfo")
@RestController
@RequestMapping("/DeptInfo")
public class DeptInfoController extends EntityController<DeptInfoService, DeptInfo, Long> {

    @Autowired
    private DeptInfoService deptInfoService;

    @GetMapping(value = "/readCustomize")
    @Operation(summary = "readCustomize", description = "Read customized department info by ID.")
    @Parameters({
            @Parameter(name = "id", description = "Data ID, number or string type.", schema = @Schema(type = "number")),
            @Parameter(name = "fields", description = "A list of field names to be read. If not specified, it defaults to all visible fields."),
            @Parameter(name = "effectiveDate", description = "Effective date for timeline model.")
    })
    @DataMask
    public ApiResponse<DeptInfo> readOne(@RequestParam Long id,
                                         @RequestParam(required = false) List<String> fields,
                                         @RequestParam(required = false) LocalDate effectiveDate) {
        ContextHolder.getContext().setEffectiveDate(effectiveDate);
        DeptInfo deptInfo = deptInfoService.getById(id, fields).orElse(null);
        return ApiResponse.success(deptInfo);
    }

}