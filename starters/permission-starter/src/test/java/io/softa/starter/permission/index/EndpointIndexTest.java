package io.softa.starter.permission.index;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.softa.starter.permission.spi.PermissionEndpointSource;
import io.softa.starter.permission.spi.PermissionEndpointSource.PermissionEndpointDef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndpointIndexTest {

    /** An explicit-endpoints permission def (endpoints verbatim, no model). */
    private static PermissionEndpointDef explicit(String id, String... endpoints) {
        return new PermissionEndpointDef(id, List.of(endpoints), null);
    }

    private EndpointIndex build(List<PermissionEndpointDef> permissions) {
        return build("", permissions);
    }

    /** Same, with the host app's context path — what Spring injects via {@code @Value}. */
    private EndpointIndex build(String contextPath, List<PermissionEndpointDef> permissions) {
        PermissionEndpointSource source = () -> permissions;
        EndpointIndex idx = new EndpointIndex(source);
        idx.contextPath = contextPath;
        idx.init();
        return idx;
    }

    // ─── validateExplicitEndpoint invariants (fail-loud at startup) ───

    @Test
    void endpointMissingSlashPrefix_throws() {
        assertThatThrownBy(() -> build(List.of(explicit("bad", "POST Employee/searchList"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must start with '/'");
    }

    @Test
    void endpointRepeatingTheContextPath_throws() {
        assertThatThrownBy(() -> build("/api/hcm",
                List.of(explicit("bad", "POST /api/hcm/Employee/searchList"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must NOT include the '/api/hcm' context path");
    }

    @Test
    void endpointEqualToTheContextPath_throws() {
        assertThatThrownBy(() -> build("/api/hcm", List.of(explicit("bad", "POST /api/hcm"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must NOT include the '/api/hcm' context path");
    }

    /**
     * The reason this check keys off the configured context path rather than the literal
     * {@code "/api"}: a controller may own an {@code /api/*} namespace <i>inside</i> the context.
     * message-starter's MailApiController is mapped to {@code /api/mail}, so under the context
     * {@code /api/hcm} the browser calls {@code /api/hcm/api/mail/templates/preview} and the
     * servletPath the index matches is {@code /api/mail/templates/preview} — legitimately starting
     * with {@code /api}. A literal prefix test rejected it and took the whole app down at boot.
     */
    @Test
    void endpointUnderAnApiNamespaceInsideTheContext_isRegistered() {
        EndpointIndex idx = build("/api/hcm",
                List.of(explicit("mail.preview", "POST /api/mail/templates/preview")));

        assertThat(idx.lookup("/api/mail/templates/preview", "POST")).containsExactly("mail.preview");
    }

    /** No context path configured (app served at the root) — there is no prefix to repeat. */
    @Test
    void endpointWithApiPrefix_whenAppServedAtRoot_isRegistered() {
        EndpointIndex idx = build(List.of(explicit("mail.send", "POST /api/mail/send")));

        assertThat(idx.lookup("/api/mail/send", "POST")).containsExactly("mail.send");
    }

    @Test
    void endpointWithUnknownVerb_throws() {
        assertThatThrownBy(() -> build(List.of(explicit("bad", "FETCH /Employee/searchList"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown HTTP verb");
    }

    @Test
    void endpointWithMissingSpace_throws() {
        assertThatThrownBy(() -> build(List.of(explicit("bad", "POST/Employee/searchList"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void endpointBlank_throws() {
        assertThatThrownBy(() -> build(List.of(explicit("bad", ""))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
    }

    // ─── lookup: exact-index hits ───

    @Test
    void lookup_exactMatch_returnsPermissionSet() {
        EndpointIndex idx = build(List.of(explicit("emp.view", "POST /Employee/searchList")));
        assertThat(idx.lookup("/Employee/searchList", "POST"))
                .containsExactly("emp.view");
    }

    @Test
    void lookup_notRegistered_returnsEmpty() {
        EndpointIndex idx = build(List.of(explicit("emp.view", "POST /Employee/searchList")));
        assertThat(idx.lookup("/Employee/somethingElse", "POST")).isEmpty();
    }

    @Test
    void lookup_verbMismatch_returnsEmpty() {
        EndpointIndex idx = build(List.of(explicit("emp.view", "POST /Employee/searchList")));
        assertThat(idx.lookup("/Employee/searchList", "GET")).isEmpty();
    }

    @Test
    void lookup_multiplePermissionsShareEndpoint_bothIntoSet() {
        // Both employee.view and department.view claim /Department/searchList
        // so an Employee-only role still gets the department-tree side panel.
        EndpointIndex idx = build(List.of(
                explicit("emp.view", "POST /Department/searchList"),
                explicit("dept.view", "POST /Department/searchList")));
        Set<String> hit = idx.lookup("/Department/searchList", "POST");
        assertThat(hit).containsExactlyInAnyOrder("emp.view", "dept.view");
    }

    // ─── lookup: pattern index ───

    @Test
    void lookup_patternWithPathParam_matchesConcreteRequest() {
        EndpointIndex idx = build(List.of(
                explicit("emp.update", "POST /Employee/onChange/{fieldName}")));
        assertThat(idx.lookup("/Employee/onChange/firstName", "POST"))
                .containsExactly("emp.update");
    }

    @Test
    void lookup_patternMismatch_returnsEmpty() {
        EndpointIndex idx = build(List.of(
                explicit("emp.update", "POST /Employee/onChange/{fieldName}")));
        // Different model → no match.
        assertThat(idx.lookup("/Department/onChange/anything", "POST")).isEmpty();
    }

    @Test
    void lookup_multiplePatternsMatchSameUri_collectedIntoSet() {
        // Two permissions register the same pattern → both surface for the caller.
        EndpointIndex idx = build(List.of(
                explicit("perm.a", "POST /X/{id}/preview"),
                explicit("perm.b", "POST /X/{id}/preview")));
        Set<String> hit = idx.lookup("/X/123/preview", "POST");
        assertThat(hit).containsExactlyInAnyOrder("perm.a", "perm.b");
    }

    @Test
    void lookup_exactPreferredOverPattern() {
        // Exact match wins — the pattern is checked only after exact miss.
        EndpointIndex idx = build(List.of(
                explicit("perm.exact", "POST /X/list"),
                explicit("perm.pat",   "POST /X/{whatever}")));
        Set<String> hit = idx.lookup("/X/list", "POST");
        // Only the exact match returns; pattern isn't consulted.
        assertThat(hit).containsExactly("perm.exact");
    }

    // ─── reload ───

    @Test
    void reloadPicksUpPermissionRowsAddedAfterStartup() {
        AtomicReference<List<PermissionEndpointDef>> rows = new AtomicReference<>(
                List.of(explicit("report.view", "POST /Report/searchPage")));
        EndpointIndex idx = new EndpointIndex(rows::get);
        idx.init();
        assertThat(idx.lookup("/Report/export", "POST")).isEmpty();

        rows.set(List.of(explicit("report.view", "POST /Report/searchPage"),
                explicit("report.export", "POST /Report/export", "GET /Report/{id}/file")));
        idx.reload();

        assertThat(idx.lookup("/Report/export", "POST")).containsExactly("report.export");
        assertThat(idx.lookup("/Report/42/file", "GET")).containsExactly("report.export");
        assertThat(idx.lookup("/Report/searchPage", "POST")).containsExactly("report.view");
    }

    @Test
    void reloadDropsEndpointsWhosePermissionIsGone() {
        AtomicReference<List<PermissionEndpointDef>> rows = new AtomicReference<>(
                List.of(explicit("report.export", "POST /Report/export")));
        EndpointIndex idx = new EndpointIndex(rows::get);
        idx.init();

        rows.set(List.of());
        idx.reload();

        assertThat(idx.lookup("/Report/export", "POST")).isEmpty();
    }

    @Test
    void aFailedReloadKeepsThePreviousIndex() {
        AtomicReference<List<PermissionEndpointDef>> rows = new AtomicReference<>(
                List.of(explicit("report.view", "POST /Report/searchPage")));
        EndpointIndex idx = new EndpointIndex(rows::get);
        idx.init();

        rows.set(List.of(explicit("report.view", "POST /Report/searchPage"),
                explicit("bad", "POST Report/export")));
        assertThatThrownBy(idx::reload).isInstanceOf(IllegalStateException.class);

        assertThat(idx.lookup("/Report/searchPage", "POST")).containsExactly("report.view");
    }
}
