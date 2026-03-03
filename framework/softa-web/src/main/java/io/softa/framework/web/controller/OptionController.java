package io.softa.framework.web.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.meta.MetaOptionItem;
import io.softa.framework.orm.meta.OptionManager;
import io.softa.framework.web.response.ApiResponse;

/**
 * SysOptionSet Model Controller
 */
@Tag(name = "SysOptionSet")
@RestController
@RequestMapping("/SysOptionSet")
public class OptionController {

    /**
     * Get the option set items of the specified option set code.
     *
     * @param optionSetCode optionSet Code
     * @return option set items
     */
    @GetMapping("/getOptionItems/{optionSetCode}")
    @Operation(summary = "getOptionItems", description = "Get the option set items of the specified option set code.")
    @Parameter(name = "optionSetCode", description = "Option set code", required = true)
    public ApiResponse<List<MetaOptionItem>> getOptionItems(@PathVariable String optionSetCode) {
        Assert.notBlank(optionSetCode, "Option set code cannot be empty.");
        return ApiResponse.success(OptionManager.getMetaOptionItems(optionSetCode));
    }
}