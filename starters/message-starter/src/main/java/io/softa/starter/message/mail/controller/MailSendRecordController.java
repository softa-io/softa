package io.softa.starter.message.mail.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.web.dto.GetByIdParams;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.dto.QueryParams;
import io.softa.framework.web.dto.SearchListParams;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.shared.TenantScopes;

/**
 * REST controller for outgoing mail records: a read-only audit log plus the manual
 * {@code retry} operation. Records are created automatically by MessageService and
 * must not be created via API.
 *
 * <p><b>Reads are tenant-scoped, and the platform super-admin additionally sees mail addressed to
 * the admin roster.</b> A send record is stamped with the tenant the mail belongs to — the consumer
 * restores {@code MailRequestMessage.tenantId} before writing it — so an invitation the super-admin
 * sends while creating a tenant's first admin lands in THAT tenant's books, not theirs. Without this
 * they are the only person who knows the mail was requested and the only person who cannot see
 * whether it went out; on a brand-new tenant there is nobody else who could look, since the admin
 * being invited has not set a password yet.
 *
 * <p>Mirrors {@code UserAccountController} / {@code UserInvitationController}, which already open the
 * same roster on the account and invitation pages. The join is different only because a mail record
 * has no {@code userId} to key on — it names recipients by address, so the roster is resolved to
 * emails and matched against {@code toAddresses}.
 */
@Tag(name = "MailSendRecord")
@RestController
@RequestMapping("/MailSendRecord")
public class MailSendRecordController
        extends EntityController<MailSendRecordService, MailSendRecord, Long> {

    private static final String MODEL = "MailSendRecord";
    /** Holding either of these makes an account part of the roster Ops is responsible for. */
    private static final List<String> ADMIN_ROLE_CODES = List.of("SUPER_ADMIN", "TENANT_ADMIN");

    @Autowired
    private ModelService<Long> modelService;

    /**
     * Manually requeue a stalled or failed record for delivery. Accepts
     * PENDING / RETRY / FAILED / DEAD_LETTER; rejects SENT and in-flight SENDING.
     * Safe to call repeatedly — the delivery claim is CAS-guarded, so a duplicate
     * requeue no-ops at the consumer.
     */
    @Operation(summary = "Manually requeue one mail record for delivery")
    @PostMapping("/retry")
    public ApiResponse<Boolean> retry(@RequestParam Long id) {
        return ApiResponse.success(service.retry(id));
    }

    @Operation(summary = "Search MailSendRecord page — tenant-scoped (super-admin also sees the admin roster)")
    @PostMapping("/searchPage")
    public ApiResponse<Page<Map<String, Object>>> searchPage(@RequestBody(required = false) QueryParams queryParams) {
        QueryParams params = queryParams == null ? new QueryParams() : queryParams;
        FlexQuery flexQuery = QueryParams.convertParamsToFlexQuery(params);
        Page<Map<String, Object>> page = Page.of(params.getPageNumber(), params.getPageSize());
        return ApiResponse.success(inRosterScope(() -> {
            flexQuery.setFilters(scopeByTenant(flexQuery.getFilters()));
            return modelService.searchPage(MODEL, flexQuery, page);
        }));
    }

    @Operation(summary = "Search MailSendRecord list — same scoping as searchPage")
    @PostMapping("/searchList")
    public ApiResponse<List<Map<String, Object>>> searchList(
            @RequestBody(required = false) SearchListParams searchListParams) {
        SearchListParams params = searchListParams == null ? new SearchListParams() : searchListParams;
        FlexQuery flexQuery = SearchListParams.convertParamsToFlexQuery(params);
        return ApiResponse.success(inRosterScope(() -> {
            flexQuery.setFilters(scopeByTenant(flexQuery.getFilters()));
            return modelService.searchList(MODEL, flexQuery);
        }));
    }

    /**
     * Typed shadow of the generic {@code /MailSendRecord/getById} — the detail read behind the
     * roster.
     *
     * <p>The list the platform super-admin browses spans tenants ({@link #searchPage} /
     * {@link #searchList} above), but the generic getById runs tenant-filtered, so opening any row
     * from another tenant answers "Record Not Found" — the invitation is listed and then will not
     * open. Same window, same caller gate, and the SAME BOUNDS: the detail read is re-checked
     * against {@link #scopeToAdminMail}, so the super-admin opens exactly what the roster lists and
     * an id outside it answers like a nonexistent record. Everyone else reads exactly what the
     * generic path read.
     */
    @Operation(summary = "Get one send record by id — the platform super-admin reads its cross-tenant roster")
    @PostMapping("/getById")
    public ApiResponse<Map<String, Object>> getById(@RequestBody GetByIdParams getByIdParams) {
        Assert.notNull(getByIdParams.getId(), "The ID of the data to be read cannot be null!");
        Long id = IdUtils.formatId(MODEL, getByIdParams.getId());
        SubQueries subQueries = new SubQueries();
        if (getByIdParams.getSubQueries() != null && !getByIdParams.getSubQueries().isEmpty()) {
            subQueries.setQueryMap(getByIdParams.getSubQueries());
        }
        return ApiResponse.success(inRosterScope(() -> {
            // Roster membership first (super-admin only — everyone else never enters the window and
            // stays on the ORM's own tenant filter). Both the check and the roster resolution must
            // sit inside the window, same as the list reads.
            if (outsideRoster(id)) {
                return null;   // outside the roster — same answer as a nonexistent record
            }
            return modelService
                    .getById(MODEL, id, getByIdParams.getFields(), subQueries, ConvertType.REFERENCE)
                    .orElse(null);
        }));
    }

    /**
     * Whether {@code id} falls outside what this caller's roster lists — the detail read's half of
     * the same bound {@link #scopeToAdminMail} puts on the list.
     *
     * <p>Package-private so it can be pinned directly: the surrounding getById is framework plumbing
     * (id coercion, sub-queries) that a unit test can only reach by standing up the whole model
     * catalogue, while this condition is the part that decides whether a super-admin opening another
     * tenant's invitation gets the record or "not found".
     *
     * <p>Always false for anyone else — they never enter the cross-tenant window, so the ORM's own
     * tenant filter is already the only bound they need.
     */
    boolean outsideRoster(Long id) {
        return isPlatformSuperAdmin() && modelService.count(MODEL,
                scopeToAdminMail(new Filters().eq(ModelConstant.ID, id))) == 0;
    }

    /** True when the caller holds the platform super-admin role. */
    private static boolean isPlatformSuperAdmin() {
        Context context = ContextHolder.getContext();
        Set<String> roleCodes = context == null ? null : context.getRoleCodes();
        return roleCodes != null && roleCodes.contains("SUPER_ADMIN");
    }

    /**
     * Run the read in a cross-tenant window — super-admin only.
     *
     * <p>Both the roster lookup and the query itself have to sit inside it: {@code Role} and
     * {@code UserRoleRel} are multiTenant, so resolving the roster outside would find only the
     * platform tenant's admin roles and collapse to nothing; and the outer read has to be unnarrowed
     * too, or the ORM ANDs {@code tenant_id = platform} onto {@code roster OR tenant_id = platform}
     * and silently drops the roster half. The scope filter is what bounds the result — the window
     * only stops the ORM from bounding it a second time, more narrowly than intended.
     */
    private <T> T inRosterScope(Supplier<T> read) {
        if (!isPlatformSuperAdmin()) {
            return read.get();
        }
        Context crossTenant = ContextHolder.cloneContext();
        crossTenant.setCrossTenant(true);
        return ContextHolder.callWith(crossTenant, read::get);
    }

    private Filters scopeByTenant(Filters filters) {
        if (!TenantScopes.multiTenancyEnabled()) {
            return filters;   // single-tenant: no tenant dimension
        }
        if (!isPlatformSuperAdmin()) {
            return filters;   // the ORM already narrows this caller's reads to its own tenant
        }
        return scopeToAdminMail(filters);
    }

    /**
     * Mail addressed to an account holding an admin role in any tenant, plus every record in the
     * super-admin's own tenant.
     *
     * <p>Recipient-keyed rather than tenant-keyed on purpose. Opening whole tenants would hand Ops
     * the subject line and recipient of every payslip, signing notice and preboarding mail those
     * companies send; the question this page has to answer for them is narrower — did the invitation
     * I just sent go out. An admin's address answers exactly that and nothing else.
     *
     * <p>Matched as a plain substring, because that is what the column holds: MULTI_STRING is
     * written by {@code MultiStringProcessor} as the bare values joined on commas — no quotes, no
     * brackets — whatever the DDL comment claims about JSON. A bounded match is therefore not
     * available, and an address that is a suffix of a longer one at the same domain
     * ({@code a@b.com} inside {@code xa@b.com}) can over-match. That widens what Ops sees by one
     * record; it cannot narrow it, and both addresses would have to belong to real accounts for it
     * to happen at all. CC and BCC live in their own columns and are not searched.
     *
     * <p>Reached through {@link ModelService} by model name rather than through user-starter's
     * services: message-starter does not depend on it, and must not start.
     */
    private Filters scopeToAdminMail(Filters filters) {
        List<Long> adminRoleIds = idsOf(modelService.searchList("Role",
                new FlexQuery(new Filters().in("code", ADMIN_ROLE_CODES))), ModelConstant.ID);
        List<String> adminEmails = adminRoleIds.isEmpty() ? List.of() : emailsOfAdmins(adminRoleIds);

        Filters roster = null;
        for (String email : adminEmails) {
            Filters one = new Filters().contains("toAddresses", email);
            roster = roster == null ? one : Filters.or(roster, one);
        }
        Long ownTenant = ContextHolder.getContext() == null ? null : ContextHolder.getContext().getTenantId();
        Filters own = ownTenant == null ? null : new Filters().eq(ModelConstant.TENANT_ID, ownTenant);

        // No admins and no tenant of one's own would leave the scope unbounded — which for a
        // cross-tenant window means every record in the deployment. A filter that matches nothing is
        // the safe reading of "nothing to show".
        Filters scope;
        if (roster == null && own == null) {
            scope = new Filters().eq(ModelConstant.ID, -1L);
        } else if (roster == null) {
            scope = own;
        } else if (own == null) {
            scope = roster;
        } else {
            scope = Filters.or(roster, own);
        }
        return filters == null ? scope : Filters.and(filters, scope);
    }

    private List<String> emailsOfAdmins(List<Long> adminRoleIds) {
        List<Long> adminUserIds = idsOf(modelService.searchList("UserRoleRel",
                new FlexQuery(new Filters().in("roleId", adminRoleIds))), "userId");
        if (adminUserIds.isEmpty()) {
            return List.of();
        }
        return modelService.searchList("UserAccount",
                        new FlexQuery(new Filters().in(ModelConstant.ID, adminUserIds))).stream()
                .map(row -> row.get("email"))
                .filter(v -> v instanceof String s && !s.isBlank())
                .map(String::valueOf)
                .distinct()
                .toList();
    }

    private static List<Long> idsOf(List<Map<String, Object>> rows, String field) {
        return rows.stream()
                .map(row -> row.get(field))
                .filter(v -> v instanceof Number)
                .map(v -> ((Number) v).longValue())
                .distinct()
                .toList();
    }
}
