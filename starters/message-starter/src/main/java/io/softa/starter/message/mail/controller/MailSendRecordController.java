package io.softa.starter.message.mail.controller;

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
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.shared.TenantScopes;
import io.softa.starter.message.shared.dto.SendRecordIdsDTO;

/**
 * The platform's send log: every tenant's mail and the platform's own, bodies included, plus the
 * manual {@code retry} / {@code retryByIds} operations. Records are created by MessageService and
 * must not be created via API.
 * <p>
 * The table is not multi-tenant, so nothing narrows it to a tenant: these endpoints belong on platform
 * menus only, and the permission layer is what keeps tenants out of every route of the page. As a
 * second line behind it, the list and detail reads and the retries here refuse a tenant caller in code
 * — the other generic model routes of the table are left to the permission layer. Tenants read and
 * retry their own send log through {@code TenantMailSendRecord}.
 */
@Tag(name = "MailSendRecord")
@RestController
@RequestMapping("/MailSendRecord")
public class MailSendRecordController
        extends EntityController<MailSendRecordService, MailSendRecord, Long> {

    private static final String MODEL = "MailSendRecord";

    @Autowired
    private ModelService<Long> modelService;

    /**
     * Manually requeue a stalled or failed record for delivery. Accepts
     * PENDING / RETRY / FAILED / DEAD_LETTER; rejects SENT and in-flight SENDING.
     * Safe to call repeatedly — the delivery claim is CAS-guarded, so a duplicate
     * requeue never sends twice.
     */
    @Operation(summary = "Manually requeue one mail record for delivery — refused for tenant callers")
    @PostMapping("/retry")
    public ApiResponse<Boolean> retry(@RequestParam Long id) {
        requirePlatformCaller();
        return ApiResponse.success(service.retry(id));
    }

    /**
     * Requeue the selected records, skipping SENT and in-flight SENDING ones
     * instead of failing. Returns how many were requeued.
     */
    @Operation(summary = "Manually requeue the selected mail records for delivery — refused for tenant callers")
    @PostMapping("/retryByIds")
    public ApiResponse<Integer> retryByIds(@RequestBody @Valid SendRecordIdsDTO request) {
        requirePlatformCaller();
        return ApiResponse.success(service.retryByIds(request.getIds()));
    }

    @Operation(summary = "Search the platform's send log — refused for tenant callers")
    @PostMapping("/searchPage")
    public ApiResponse<Page<Map<String, Object>>> searchPage(@RequestBody(required = false) QueryParams queryParams) {
        requirePlatformCaller();
        QueryParams params = queryParams == null ? new QueryParams() : queryParams;
        FlexQuery flexQuery = QueryParams.convertParamsToFlexQuery(params);
        Page<Map<String, Object>> page = Page.of(params.getPageNumber(), params.getPageSize());
        return ApiResponse.success(modelService.searchPage(MODEL, flexQuery, page));
    }

    @Operation(summary = "Search the platform's send log as a list — refused for tenant callers")
    @PostMapping("/searchList")
    public ApiResponse<List<Map<String, Object>>> searchList(
            @RequestBody(required = false) SearchListParams searchListParams) {
        requirePlatformCaller();
        SearchListParams params = searchListParams == null ? new SearchListParams() : searchListParams;
        return ApiResponse.success(modelService.searchList(MODEL, SearchListParams.convertParamsToFlexQuery(params)));
    }

    @Operation(summary = "Get one send record by id — refused for tenant callers")
    @PostMapping("/getById")
    public ApiResponse<Map<String, Object>> getById(@RequestBody GetByIdParams getByIdParams) {
        requirePlatformCaller();
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

    private static void requirePlatformCaller() {
        TenantScopes.requirePlatformCaller("The mail send log of every tenant (a tenant reads its own from TenantMailSendRecord)");
    }
}
