package io.softa.starter.studio.release.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.studio.release.entity.DesignPortfolio;
import io.softa.starter.studio.release.enums.DesignPortfolioStatus;
import io.softa.starter.studio.release.service.DesignPortfolioService;

/**
 * DesignPortfolio Model Controller
 */
@Tag(name = "DesignPortfolio")
@RestController
@RequestMapping("/DesignPortfolio")
public class DesignPortfolioController extends EntityController<DesignPortfolioService, DesignPortfolio, Long> {

    /**
     * Activate the portfolio.
     *
     * @param id portfolio ID
     * @return true / Exception
     */
    @Operation(description = "Activate the Portfolio.")
    @PostMapping(value = "/activate")
    @Parameter(name = "id", description = "Portfolio ID")
    public ApiResponse<Boolean> activate(Long id) {
        service.transitionStatus(id, DesignPortfolioStatus.ACTIVE);
        return ApiResponse.success(true);
    }

    /**
     * Archive the portfolio.
     *
     * @param id portfolio ID
     * @return true / Exception
     */
    @Operation(description = "Archive the Portfolio.")
    @PostMapping(value = "/archive")
    @Parameter(name = "id", description = "Portfolio ID")
    public ApiResponse<Boolean> archive(Long id) {
        service.transitionStatus(id, DesignPortfolioStatus.ARCHIVED);
        return ApiResponse.success(true);
    }

}
