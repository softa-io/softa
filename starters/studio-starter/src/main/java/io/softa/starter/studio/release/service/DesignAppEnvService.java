package io.softa.starter.studio.release.service;

import java.util.List;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.studio.release.dto.DriftEnvelopeDTO;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.entity.DesignAppEnv;

/**
 * DesignAppEnv Model Service Interface
 */
public interface DesignAppEnvService extends EntityService<DesignAppEnv, Long> {

    /**
     * Take a snapshot of the expected runtime metadata state for the given environment.
     * <p>
     * Computes the full expected state by applying {@code mergedChanges} on top of the
     * latest snapshot (or empty baseline for the first deployment). The result is stored
     * on {@code DesignAppEnvSnapshot}, keyed uniquely by {@code (appId, envId, deploymentId)}.
     *
     * @param envId         Environment ID
     * @param deploymentId  Deployment ID that produced this snapshot
     * @param mergedChanges the merged version changes that were deployed
     */
    void takeSnapshot(Long envId, Long deploymentId, List<ModelChangesDTO> mergedChanges);

    /**
     * Return the cached drift between the design-time snapshot and the runtime metadata
     * for the given environment. The cache is refreshed by {@link #refreshDrift(Long)} —
     * fired automatically once after every successful deployment and on demand from the
     * manual refresh endpoint — so this call is an O(1) DB read.
     * <p>
     * Returns an empty list when there is no cached drift record (drift has never been
     * checked for this env), or when the last check found no drift.
     *
     * @param envId Environment ID
     * @return List of model changes representing drift between snapshot and runtime
     */
    List<ModelChangesDTO> compareDesignWithRuntime(Long envId);

    /**
     * Drift-oriented view of {@link #compareDesignWithRuntime(Long)} for UI consumption,
     * wrapped with the cache metadata (lastCheckedTime / checkStatus / errorMessage) so
     * the frontend can render a freshness hint without a second round-trip.
     * <p>
     * Reshapes the deploy-direction graph returned by {@link #compareDesignWithRuntime(Long)}
     * into rows that carry {@code expected} (snapshot) and {@code actual} (runtime) sides
     * directly, tagged with a {@code DriftKind} read from the operator's perspective
     * (RUNTIME_ADDED / RUNTIME_DELETED / RUNTIME_MODIFIED). The deploy-direction view is
     * still served to the internal apply path; this method exists so the read API does
     * not leak that orientation to clients.
     *
     * @param envId Environment ID
     * @return drift envelope; {@code reports} is empty when no drift / never checked
     */
    DriftEnvelopeDTO getDriftEnvelope(Long envId);

    /**
     * Recompute the drift between the design-time snapshot and the runtime metadata
     * for the given environment, and upsert the result into {@code DesignAppEnvDrift}.
     * <p>
     * The comparison is expensive — it fetches runtime data for every version-controlled
     * model (possibly via HTTP RPC) and diffs the rows — so it runs in parallel across
     * models and is gated behind this explicit method rather than the public read API.
     * <p>
     * Recoverable errors (runtime unreachable) are captured on the drift record with
     * {@code checkStatus = FAILURE} rather than bubbled up, so a caller refreshing many
     * envs in sequence does not abort at the first unreachable one.
     *
     * @param envId Environment ID
     */
    void refreshDrift(Long envId);

    /**
     * Issue a fresh Ed25519 keypair for the given env and return the base64-encoded
     * public key for the operator to install on the runtime side.
     * <p>
     * The private key is written to {@code DesignAppEnv.privateKey} (ORM-encrypted at
     * rest) and never returned by any read API. The runtime trusts the new key once
     * the operator has updated its {@code system.runtime-public-key}
     * entry —
     * since each runtime pairs with exactly one env, this is an atomic swap rather
     * than a multi-key rotation.
     *
     * @param envId Environment ID
     * @return the newly-issued public key, base64-encoded X.509
     */
    IssuedKey issueKey(Long envId);

    /**
     * Overwrite design-time metadata with the current runtime state of the given env.
     * <p>
     * Covers two operator use cases: (a) first-time initialisation where design-time is
     * empty and the runtime already carries the app's metadata, and (b) drift repair where
     * runtime has diverged (manual SQL, ad-hoc fixes) and the operator accepts the runtime
     * version as the new truth. Both cases dispatch the same work — apply the inverted
     * drift to design-time — and diverge only on whether the drift cache is refreshed first.
     * <p>
     * Flow:
     * <ol>
     *   <li>Acquire the per-env mutex (envStatus: STABLE → DEPLOYING) via compare-and-set;
     *       an in-flight deployment or another import fails fast here.</li>
     *   <li>If {@code useCached} is false, call {@link #refreshDrift(Long)} first so we apply
     *       drift computed against the current runtime, not a stale cached result.</li>
     *   <li>Invert every entry in the cached drift — runtime-only rows become CREATE,
     *       matched-but-different rows become UPDATE with runtime values,
     *       snapshot-only rows become DELETE — and write those onto the Design models
     *       via {@code ModelService}. Inserts flow parent→child, deletes child→parent
     *       to respect referential order.</li>
     *   <li>Write a fresh snapshot reflecting the post-import state, keyed by the synthetic
     *       version id in the {@code deploymentId} slot (globally unique — CosID).</li>
     *   <li>Clear the drift cache, then release the env mutex.</li>
     * </ol>
     * No-op when the drift is empty: the mutex is acquired and released but no writes happen
     * against Design models, so re-running the operation is cheap.
     *
     * @param envId     Environment ID
     * @param useCached {@code true} to apply the already-cached drift record (cheaper,
     *                  but may miss runtime changes since the last {@code refreshDrift});
     *                  {@code false} to refresh drift inline first.
     */
    void applyDrift(Long envId, boolean useCached);

    /**
     * Plaintext return payload for {@link #issueKey(Long)} — never holds the private
     * half, which stays on the server.
     */
    record IssuedKey(String publicKey) {}
}
