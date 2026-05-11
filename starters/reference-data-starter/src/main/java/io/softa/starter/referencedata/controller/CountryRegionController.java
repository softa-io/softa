package io.softa.starter.referencedata.controller;

import java.util.Comparator;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.referencedata.dto.DialCodeItemDTO;
import io.softa.starter.referencedata.entity.CountryRegion;
import io.softa.starter.referencedata.service.CountryRegionService;

/**
 * CountryRegion Controller. CRUD is served by the metadata-driven generic
 * endpoints; this class only adds the custom projections that the generic
 * pipeline cannot express.
 */
@Tag(name = "CountryRegion")
@RestController
@RequestMapping("/CountryRegion")
public class CountryRegionController extends EntityController<CountryRegionService, CountryRegion, Long> {

    @Operation(summary = "List dial codes",
            description = "Returns one row per country for phone-input / SMS-region selectors. "
                    + "Ordered by English name asc. dialCode is not unique across countries.")
    @GetMapping("/listDialCodes")
    public ApiResponse<List<DialCodeItemDTO>> listDialCodes() {
        List<DialCodeItemDTO> items = service.searchList().stream()
                .filter(c -> StringUtils.hasText(c.getDialCode()))
                .map(CountryRegionController::toDialCodeItem)
                .sorted(Comparator.comparing(DialCodeItemDTO::getName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return ApiResponse.success(items);
    }

    private static DialCodeItemDTO toDialCodeItem(CountryRegion c) {
        DialCodeItemDTO item = new DialCodeItemDTO();
        item.setCode(c.getCode());
        item.setName(c.getName());
        item.setDialCode(c.getDialCode());
        item.setAlpha3Code(c.getAlpha3Code());
        return item;
    }
}
