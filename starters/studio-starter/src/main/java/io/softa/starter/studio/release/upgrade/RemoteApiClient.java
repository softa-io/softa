package io.softa.starter.studio.release.upgrade;

import java.util.List;
import java.util.Map;

import io.softa.starter.metadata.dto.MetadataUpgradePackage;
import io.softa.starter.studio.release.entity.DesignAppEnv;

public interface RemoteApiClient {

    /**
     * Dispatch a metadata upgrade to a remote runtime env.
     * <p>
     * The runtime side returns 202 as soon as the envelope is accepted and applies
     * the packages on a background thread; completion lands asynchronously on the
     * {@code callbackUrl} with {@code callbackToken} echoed in the callback header.
     * This method returns once the runtime has acknowledged the dispatch — the
     * caller is expected to leave the studio-side deployment in {@code DEPLOYING}
     * until the webhook transitions it to {@code SUCCESS} / {@code FAILURE}.
     *
     * @param appEnv         target environment (supplies endpoint + signing key)
     * @param modelPackages  runtime metadata packages to apply
     * @param callbackUrl    absolute URL the runtime must POST completion to
     * @param callbackToken  one-time token — echoed in the callback header and
     *                       used by the studio webhook to match the deployment
     */
    void remoteUpgrade(DesignAppEnv appEnv,
                       List<MetadataUpgradePackage> modelPackages,
                       String callbackUrl,
                       String callbackToken);

    /**
     * Fetch runtime metadata for a specific model from the remote environment, scoped to
     * the env's owning app. The runtime may host several apps that share metadata tables,
     * so the {@code appId} filter keeps the studio from pulling a sibling app's rows.
     *
     * @param appEnv           target environment (supplies endpoint, signing key, appId)
     * @param runtimeModelName runtime model name (e.g. "SysModel")
     * @return list of runtime row data
     */
    List<Map<String, Object>> fetchRuntimeMetadata(DesignAppEnv appEnv, String runtimeModelName);
}