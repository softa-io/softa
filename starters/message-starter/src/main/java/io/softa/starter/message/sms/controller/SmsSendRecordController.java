package io.softa.starter.message.sms.controller;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.dto.GetByIdParams;
import io.softa.framework.web.dto.QueryParams;
import io.softa.framework.web.dto.SearchListParams;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.shared.TenantScopes;
import io.softa.starter.message.shared.dto.SendRecordIdsDTO;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.service.SmsSendRecordService;

/**
 * The platform's SMS send log (audit log) plus the manual {@code retry} / {@code retryByIds}
 * operations. SMS is a platform service and the table is not multi-tenant, so these endpoints belong
 * on platform menus only; as a second line behind the menus, the reads and retries here refuse a
 * tenant caller outright — the log holds every tenant's login and reset codes.
 */
@Tag(name = "SmsSendRecord")
@RestController
@RequestMapping("/SmsSendRecord")
public class SmsSendRecordController
        extends EntityController<SmsSendRecordService, SmsSendRecord, Long> {

    private static final String MODEL = "SmsSendRecord";
    private static final String PAGE = "The SMS send log";

    @Autowired
    private ModelService<Long> modelService;

    /**
     * Manually requeue a stalled or failed record for delivery. Accepts
     * PENDING / RETRY / FAILED / DEAD_LETTER; rejects SENT and in-flight SENDING.
     * Safe to call repeatedly — the delivery claim is CAS-guarded.
     */
    @Operation(summary = "Manually requeue one SMS record for delivery")
    @PostMapping("/retry")
    public ApiResponse<Boolean> retry(@RequestParam Long id) {
        TenantScopes.requirePlatformCaller(PAGE);
        return ApiResponse.success(service.retry(id));
    }

    /**
     * Requeue the selected records, skipping SENT and in-flight SENDING ones
     * instead of failing. Returns how many were requeued.
     */
    @Operation(summary = "Manually requeue the selected SMS records for delivery")
    @PostMapping("/retryByIds")
    public ApiResponse<Integer> retryByIds(@RequestBody @Valid SendRecordIdsDTO request) {
        TenantScopes.requirePlatformCaller(PAGE);
        return ApiResponse.success(service.retryByIds(request.getIds()));
    }

    @Operation(summary = "Search the SMS send log — refused for tenant callers")
    @PostMapping("/searchPage")
    public ApiResponse<Page<Map<String, Object>>> searchPage(@RequestBody(required = false) QueryParams queryParams) {
        TenantScopes.requirePlatformCaller(PAGE);
        QueryParams params = queryParams == null ? new QueryParams() : queryParams;
        FlexQuery flexQuery = QueryParams.convertParamsToFlexQuery(params);
        Page<Map<String, Object>> page = Page.of(params.getPageNumber(), params.getPageSize());
        return ApiResponse.success(modelService.searchPage(MODEL, flexQuery, page));
    }

    @Operation(summary = "Search the SMS send log as a list — refused for tenant callers")
    @PostMapping("/searchList")
    public ApiResponse<List<Map<String, Object>>> searchList(
            @RequestBody(required = false) SearchListParams searchListParams) {
        TenantScopes.requirePlatformCaller(PAGE);
        SearchListParams params = searchListParams == null ? new SearchListParams() : searchListParams;
        return ApiResponse.success(modelService.searchList(MODEL, SearchListParams.convertParamsToFlexQuery(params)));
    }

    @Operation(summary = "Get one SMS send record by id — refused for tenant callers")
    @PostMapping("/getById")
    public ApiResponse<Map<String, Object>> getById(@RequestBody GetByIdParams getByIdParams) {
        TenantScopes.requirePlatformCaller(PAGE);
        Assert.notNull(getByIdParams.getId(), "The ID of the data to be read cannot be null!");
        Long id = IdUtils.formatId(MODEL, getByIdParams.getId());
        SubQueries subQueries = new SubQueries();
        if (getByIdParams.getSubQueries() != null && !getByIdParams.getSubQueries().isEmpty()) {
            subQueries.setQueryMap(getByIdParams.getSubQueries());
        }
        return ApiResponse.success(modelService
                .getById(MODEL, id, getByIdParams.getFields(), subQueries, ConvertType.REFERENCE)
                .orElse(null));
    }
}
