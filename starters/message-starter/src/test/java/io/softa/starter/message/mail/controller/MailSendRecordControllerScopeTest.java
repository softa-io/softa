package io.softa.starter.message.mail.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.ScopedValue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.dto.QueryParams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who sees which send records.
 *
 * <p>A record is stamped with the tenant the mail belongs to, so the invitation a platform
 * super-admin sends while creating a tenant's first admin lands in THAT tenant's books. Left to the
 * ORM's tenant filter, the one person who knows the mail was requested is the one person who cannot
 * see whether it went out — and on a new tenant nobody else can look, because the admin being
 * invited has not set a password yet.
 *
 * <p>These assert the FILTER the controller builds, not rows that come back. A stubbed result would
 * satisfy any filter at all, including the one that returns another company's payslips.
 */
class MailSendRecordControllerScopeTest {

    private static final Long PLATFORM_TENANT = -1L;
    private static final Long OTHER_TENANT = 200L;

    private ModelService<Long> modelService;
    private MailSendRecordController controller;
    /** The filters the controller handed the read. */
    private AtomicReference<Filters> captured;
    /** What the membership count returns — 0 means "no such row in the roster". */
    private long countAnswer;

    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        controller = new MailSendRecordController();
        ReflectionTestUtils.setField(controller, "modelService", modelService);
        captured = new AtomicReference<>();
        countAnswer = 1L;

        when(modelService.searchList(eq("Role"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("id", 7L)));
        when(modelService.searchList(eq("UserRoleRel"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("userId", 9001L)));
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("email", "admin@acme.test")));
        when(modelService.count(eq("MailSendRecord"), any(Filters.class)))
                .thenAnswer(inv -> {
                    captured.set(inv.getArgument(1));
                    return countAnswer;
                });
        when(modelService.searchPage(eq("MailSendRecord"), any(FlexQuery.class), any(Page.class)))
                .thenAnswer(inv -> {
                    captured.set(((FlexQuery) inv.getArgument(1)).getFilters());
                    return Page.of(1, 20);
                });
    }

    @AfterEach
    void tearDown() {
        SystemConfig.env = null;
    }

    private Filters searchAs(Long tenantId, String... roleCodes) {
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        ctx.setRoleCodes(Set.of(roleCodes));
        ContextHolder.runWith(ctx, () -> controller.searchPage(new QueryParams()));
        return captured.get();
    }

    /** Run {@code action} as the platform super-admin in a multi-tenant deployment. */
    private <T> T asSuperAdmin(ScopedValue.CallableOp<T, RuntimeException> action) {
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
        Context ctx = new Context();
        ctx.setTenantId(PLATFORM_TENANT);
        ctx.setRoleCodes(Set.of("SUPER_ADMIN"));
        return ContextHolder.callWith(ctx, action);
    }

    /** Every leaf condition in the tree, flattened. */
    private static void walk(Filters filters, Consumer<FilterUnit> visit) {
        if (filters == null) {
            return;
        }
        if (filters.getFilterUnit() != null) {
            visit.accept(filters.getFilterUnit());
        }
        if (filters.getChildren() != null) {
            filters.getChildren().forEach(child -> walk(child, visit));
        }
    }

    private static boolean mentions(Filters filters, String field, String valueFragment) {
        AtomicReference<Boolean> found = new AtomicReference<>(false);
        walk(filters, unit -> {
            if (field.equals(unit.getField())
                    && String.valueOf(unit.getValue()).contains(valueFragment)) {
                found.set(true);
            }
        });
        return found.get();
    }

    @Test
    void aTenantAdminReadsOnlyItsOwnRecordsAndTheControllerAddsNothing() {
        // The ORM's tenant filter already bounds this caller. Adding a scope here would be a second,
        // narrower bound — and the roster half would silently drop rows the tenant is entitled to.
        Filters filters = searchAs(OTHER_TENANT, "TENANT_ADMIN");

        assertThat(filters).as("no scope filter for a non-super-admin").isNull();
    }

    @Test
    void aSuperAdminAlsoSeesMailAddressedToTheAdminRoster() {
        // The case this exists for: the invitation is filed under the tenant being set up, so it can
        // only be reached by recipient.
        Filters filters = searchAs(PLATFORM_TENANT, "SUPER_ADMIN");

        assertThat(mentions(filters, "toAddresses", "admin@acme.test"))
                .as("roster half must match the admin's address")
                .isTrue();
    }

    @Test
    void theAddressIsMatchedAsStoredWithNoQuotesAround() {
        // The column holds what MultiStringProcessor writes: the bare values joined on commas. An
        // earlier revision matched "admin@acme.test" WITH quotes, reading the DDL comment's claim
        // that this is a JSON array — it is not, and the filter silently matched nothing, so the
        // super-admin still saw none of the invitations they had just sent.
        Filters filters = searchAs(PLATFORM_TENANT, "SUPER_ADMIN");

        AtomicReference<String> value = new AtomicReference<>();
        walk(filters, unit -> {
            if ("toAddresses".equals(unit.getField())) {
                value.set(String.valueOf(unit.getValue()));
            }
        });
        assertThat(value.get())
                .as("matched exactly as stored — quoting it matches nothing")
                .isEqualTo("admin@acme.test");
    }

    @Test
    void theDetailReadIsBoundedByTheSameRosterAsTheList() {
        // Listing across tenants is only half the job: the generic getById runs tenant-filtered, so
        // an invitation the roster listed opens as "Record Not Found". The membership check reuses
        // scopeToAdminMail, so what the list shows is what opens — no second, narrower bound.
        asSuperAdmin(() -> controller.outsideRoster(12345L));

        assertThat(mentions(captured.get(), "toAddresses", "admin@acme.test"))
                .as("detail read must check the roster, not the caller's tenant alone")
                .isTrue();
        assertThat(mentions(captured.get(), "id", "12345"))
                .as("and it must check THIS id")
                .isTrue();
    }

    @Test
    void anIdOutsideTheRosterIsAnsweredLikeANonexistentRecord() {
        countAnswer = 0L;

        assertThat(asSuperAdmin(() -> controller.outsideRoster(12345L))).isTrue();
    }

    @Test
    void aTenantAdminNeverEntersTheRosterCheckAtAll() {
        // They never enter the cross-tenant window, so the ORM's tenant filter is already the only
        // bound they need — a second one here could only take rows away from them.
        countAnswer = 0L;

        Context ctx = new Context();
        ctx.setTenantId(OTHER_TENANT);
        ctx.setRoleCodes(Set.of("TENANT_ADMIN"));
        AtomicReference<Boolean> outside = new AtomicReference<>();
        ContextHolder.runWith(ctx, () -> outside.set(controller.outsideRoster(12345L)));
        assertThat(outside.get()).isFalse();
    }

    @Test
    void aSuperAdminWithNoRosterAndNoTenantMatchesNothingRatherThanEverything() {
        // The read runs cross-tenant, so an unbounded filter means every record in the deployment.
        when(modelService.searchList(eq("Role"), any(FlexQuery.class))).thenReturn(List.of());

        Filters filters = searchAs(null, "SUPER_ADMIN");

        assertThat(mentions(filters, "id", "-1"))
                .as("empty roster must bound to nothing, never to everything")
                .isTrue();
    }
}
