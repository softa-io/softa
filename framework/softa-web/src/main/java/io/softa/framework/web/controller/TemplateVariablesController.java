package io.softa.framework.web.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.meta.TemplateVariableSet;
import io.softa.framework.orm.meta.TemplateVariablesRegistry;
import io.softa.framework.web.response.ApiResponse;

/**
 * The sets of template variables the application declares, for a template editor to offer.
 *
 * <p>Read-only descriptions of what code hands to templates — names, labels, types, sample values —
 * and no data of any record: the dictionary a template is written against, like an option set's
 * items. A template bound to a model reads the model's metadata instead.
 */
@Tag(name = "TemplateVariables")
@RestController
@RequestMapping("/TemplateVariables")
public class TemplateVariablesController {

    /**
     * Every declared set, with its fields.
     *
     * @return the sets, ordered by name
     */
    @GetMapping("/list")
    @Operation(summary = "list", description = "List the declared sets of template variables.")
    public ApiResponse<List<TemplateVariableSet>> list() {
        return ApiResponse.success(TemplateVariablesRegistry.all());
    }

    /**
     * One declared set.
     *
     * @param name the name a template stores in {@code variableSetName}
     * @return the set and its fields
     */
    @GetMapping("/get")
    @Operation(summary = "get", description = "Get one set of template variables by name.")
    @Parameter(name = "name", description = "Name of the set, e.g. flow.approval-notice", required = true)
    public ApiResponse<TemplateVariableSet> get(@RequestParam String name) {
        Assert.notBlank(name, "The name of the template variables cannot be empty.");
        return ApiResponse.success(TemplateVariablesRegistry.find(name).orElseThrow(() ->
                new BusinessException("No template variables are declared under the name ''{0}''.", name.trim())));
    }
}
