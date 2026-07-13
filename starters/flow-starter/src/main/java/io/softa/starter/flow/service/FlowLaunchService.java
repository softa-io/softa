package io.softa.starter.flow.service;

import io.softa.starter.flow.runtime.engine.FlowLaunchResponse;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.api.PublishAndStartRequest;

/**
 * Launch facade for revision-aware runtime starts and publish-and-start flows.
 */
public interface FlowLaunchService {

    FlowLaunchResponse publishAndStart(PublishAndStartRequest request);

    /**
     * Editor test-run of the CURRENT draft canvas: compiles the saved draft
     * (compile errors surface as diagnostics), registers a debug bundle
     * (never active, hidden from revision lists, purged after retention),
     * and executes it through the normal engine. NOT a sandbox — node side
     * effects (record writes, notifications) are real.
     */
    FlowLaunchResponse debugRunDraft(Long designId, FlowStartRequest request);
}
