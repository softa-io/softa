package io.softa.starter.studio.meta.support;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IDGenerator;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.studio.release.entity.DesignAppEnv;

/**
 * Stamps the per-env identity columns onto a no-code design write row before it is
 * persisted, so every {@code design_*} row is scoped to an environment:
 * <ul>
 *   <li><b>{@code id}</b> — minted up front (CosID) when absent, so the row has a stable surrogate
 *       before persist;</li>
 *   <li><b>{@code envId}</b> — the environment the edit targets. Taken from the row when the client
 *       supplies it (the no-code UI knows the workspace env); otherwise defaulted to the app's
 *       canonical active env (lowest active env id — the same choice V16 used to seed the workspace).</li>
 * </ul>
 *
 * <p>Why this is needed: the per-env business key (env_id + business key) and its physical unique
 * index require {@code env_id} to be present on every write; a null would collide across envs and
 * break workspace uniqueness. Programmatic paths (the env cloner / merger) set these columns
 * themselves, so this stamper is the no-code HTTP boundary's counterpart.
 *
 * <p>On <b>update</b>, the per-env identity is set-once: {@code envId} is stripped from the row so an
 * edit can never re-home a row to another env. A field rename needs no special handling here — the
 * rename API captures the prior name into {@code renamedFrom}, and publish/merge pair the row by its
 * business key (or that prior key) and emit the name change as an in-place UPDATE.
 */
@Component
public class DesignWriteStamper {

    private static final String ID = "id";
    private static final String ENV_ID = "envId";
    private static final String APP_ID = "appId";

    private final ModelService<Long> modelService;

    public DesignWriteStamper(ModelService<Long> modelService) {
        this.modelService = modelService;
    }

    /** Stamp id / envId onto a single create row. */
    public void stampCreate(Map<String, Object> row) {
        if (row == null) {
            return;
        }
        if (IdUtils.convertIdToLong(row.get(ID)) == null) {
            row.put(ID, IDGenerator.generateLongId());
        }
        Long appId = IdUtils.convertIdToLong(row.get(APP_ID));
        Long envId = IdUtils.convertIdToLong(row.get(ENV_ID));
        if (envId == null) {
            row.put(ENV_ID, resolveDefaultEnvId(appId));
        } else {
            // Trust nothing from the wire: a client-supplied envId must belong to the row's app, else
            // the businessKey's "envId transitively scopes per-app" invariant is violated (cross-app write).
            assertEnvBelongsToApp(envId, appId);
        }
    }

    /**
     * On update: strip the set-once {@code envId} so an edit can never re-home a row to another env.
     * A rename needs no special handling here — the prior name is captured into {@code renamedFrom} by
     * the rename API, and publish/merge pair the row by its business key (or that prior key) at apply
     * time, so the name change rides through as a plain UPDATE.
     */
    public void stampUpdate(Map<String, Object> row) {
        if (row == null) {
            return;
        }
        row.remove(ENV_ID);
    }

    /** The app's canonical active env (lowest active env id) — the V16 workspace-seed choice. */
    private Long resolveDefaultEnvId(Long appId) {
        Assert.notNull(appId, "A design write needs `appId` to resolve its target environment.");
        Long envId = modelService
                .searchList(DesignAppEnv.class.getSimpleName(),
                        new FlexQuery(new Filters().eq(APP_ID, appId).eq("active", true)))
                .stream()
                .map(env -> IdUtils.convertIdToLong(env.get(ID)))
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        Assert.notNull(envId, "App {0} has no active environment to scope the design write to.", appId);
        return envId;
    }

    /** A client-supplied envId is accepted only if it exists and belongs to the row's app (any state). */
    private void assertEnvBelongsToApp(Long envId, Long appId) {
        Assert.notNull(appId, "A design write with an explicit envId also needs appId to validate it.");
        boolean belongs = !modelService
                .searchList(DesignAppEnv.class.getSimpleName(),
                        new FlexQuery(new Filters().eq(ID, envId).eq(APP_ID, appId)))
                .isEmpty();
        Assert.isTrue(belongs, "Env {0} does not belong to app {1} — cross-app design write rejected.", envId, appId);
    }
}
