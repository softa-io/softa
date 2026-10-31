package io.softa.starter.metadata.service.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.CascadeFieldWalker;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.meta.WithheldFields;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.metadata.checksum.AggregateChecksumIndex;
import io.softa.starter.metadata.controller.dto.MetaFieldDTO;
import io.softa.starter.metadata.controller.dto.MetaModelDTO;
import io.softa.starter.metadata.controller.dto.PathResolution;
import io.softa.starter.metadata.controller.dto.ResolveCascadedPathsResponse;
import io.softa.starter.metadata.dto.RuntimeChecksumsDTO;
import io.softa.starter.metadata.entity.*;
import io.softa.starter.metadata.message.InnerBroadcastProducer;
import io.softa.starter.metadata.message.dto.InnerBroadcastMessage;
import io.softa.starter.metadata.message.enums.InnerBroadcastType;
import io.softa.starter.metadata.service.MetadataService;

/**
 * Metadata query + runtime-export service (the read half of the studio↔runtime contract). Applying an
 * incremental change set is {@link MetadataApplyServiceImpl}.
 */
@Slf4j
@Service
public class MetadataServiceImpl implements MetadataService {

    private final ModelService<Serializable> modelService;
    private final InnerBroadcastProducer innerBroadcastProducer;
    private final PermissionService permissionService;

    public MetadataServiceImpl(ModelService<Serializable> modelService,
                               InnerBroadcastProducer innerBroadcastProducer,
                               PermissionService permissionService) {
        this.modelService = modelService;
        this.innerBroadcastProducer = innerBroadcastProducer;
        this.permissionService = permissionService;
    }

    /**
     * Get the MetaModelDTO object by modelName.
     *
     * <p>Without the fields withheld from the caller: the sensitive fields none of their roles grants
     * on any row ({@link PermissionService#getHiddenSensitiveFields}). Their values never reach this
     * caller, so neither does their definition — a screen offers no input, column or filter for them.
     * Fields hidden on some records only stay; which records is a per-record answer.
     *
     * <p>A relation onto a model the caller can read nothing of goes with them: when every field of the
     * related model but its plumbing is withheld, the relation would offer a column, an export field and
     * a filter that can only ever come back empty ({@link WithheldFields}).
     *
     * @param modelName model name
     * @return metaModelDTO object
     */
    @Override
    public MetaModelDTO getMetaModelDTO(String modelName) {
        return withoutWithheldFields(MetadataDtoMapper.toModelDTO(modelName),
                permissionService.getHiddenSensitiveFields());
    }

    /** A path segment naming a field withheld from the caller. */
    private record WithheldSegment(int index, String model, String field) {}

    /** {@code dto} with the caller's withheld fields of its model removed; the DTO is built per request. */
    private static MetaModelDTO withoutWithheldFields(MetaModelDTO dto, Map<String, Set<String>> withheld) {
        if (withheld.isEmpty() || dto.getModelFields() == null) {
            return dto;
        }
        Map<String, MetaFieldDTO> kept = new LinkedHashMap<>(dto.getModelFields());
        kept.values().removeIf(field -> WithheldFields.isWithheld(dto.getModelName(), field.getFieldName(),
                field.getFieldType(), field.getRelatedModel(), withheld));
        dto.setModelFields(kept);
        return dto;
    }

    /**
     * Resolve cascaded field paths from {@code rootModel} in one round-trip.
     * <p>
     * Failure isolation: each path walks independently via {@link CascadeFieldWalker}
     * with a local closure / access-fields collector; the local state is merged into
     * the request-wide state only when the walk succeeds, so a failed path can never
     * pollute the {@code metaModels} closure or the permission-check input.
     * <p>
     * Permission is enforced at request level: any forbidden model/field on the
     * union of successful paths raises a {@code PermissionException} (HTTP 403).
     * <p>
     * A path through a field withheld from the caller resolves as that field not found, the same
     * answer {@link #getMetaModelDTO} gives by leaving it out, and the related models it returns
     * leave such fields out too.
     */
    @Override
    public ResolveCascadedPathsResponse resolveCascadedPaths(String rootModel, List<String> paths) {
        Assert.notBlank(rootModel, "rootModel cannot be empty.");
        Assert.notEmpty(paths, "paths cannot be empty.");
        ModelManager.validateModel(rootModel);

        // Closure holds only the related models reachable from successful paths;
        // the root is excluded because the caller necessarily already has it
        // (the page is rendering against rootModel).
        LinkedHashSet<String> closure = new LinkedHashSet<>();
        Map<String, Set<String>> accessFields = new HashMap<>();
        List<PathResolution> resolutions = new ArrayList<>(paths.size());
        Map<String, Set<String>> withheld = permissionService.getHiddenSensitiveFields();

        for (String path : paths) {
            LinkedHashSet<String> localClosure = new LinkedHashSet<>();
            Map<String, Set<String>> localAccess = new HashMap<>();
            // The first segment naming a field withheld from the caller.
            WithheldSegment[] firstWithheld = new WithheldSegment[1];
            CascadeFieldWalker.Visitor collector = new CascadeFieldWalker.Visitor() {
                @Override
                public void onSegment(int index, String currentModel, MetaField field) {
                    localAccess.computeIfAbsent(currentModel, k -> new HashSet<>())
                            .add(field.getFieldName());
                    if (firstWithheld[0] == null && WithheldFields.isWithheld(currentModel, field.getFieldName(),
                            field.getFieldType(), field.getRelatedModel(), withheld)) {
                        firstWithheld[0] = new WithheldSegment(index, currentModel, field.getFieldName());
                    }
                }
                @Override
                public void onAdvance(int index, MetaField field, String nextModel) {
                    localClosure.add(nextModel);
                }
            };

            switch (CascadeFieldWalker.walk(rootModel, path, collector)) {
                case CascadeFieldWalker.Result.Ok(MetaField ignored) when firstWithheld[0] != null -> {
                    // Worded as the walker words a field that does not exist: the definition is
                    // withheld, so its existence is not confirmed either.
                    WithheldSegment w = firstWithheld[0];
                    resolutions.add(PathResolution.failure(path, CascadeFieldWalker.ErrorKind.FIELD_NOT_FOUND,
                            w.index(), "Field '" + w.field() + "' not found on model '" + w.model() + "'"));
                }
                case CascadeFieldWalker.Result.Ok(MetaField leaf) -> {
                    closure.addAll(localClosure);
                    localAccess.forEach((model, fields) ->
                            accessFields.computeIfAbsent(model, k -> new HashSet<>()).addAll(fields));
                    resolutions.add(PathResolution.success(path, MetadataDtoMapper.toFieldDTO(leaf)));
                }
                case CascadeFieldWalker.Result.Failure f ->
                        resolutions.add(PathResolution.failure(path, f.kind(), f.errorAt(), f.message()));
            }
        }

        // Request-level permission check on the union of successful paths.
        permissionService.checkModelCascadeFieldsAccess(rootModel, accessFields, AccessType.READ);

        List<MetaModelDTO> metaModels = closure.stream()
                .map(model -> withoutWithheldFields(MetadataDtoMapper.toModelDTO(model), withheld))
                .toList();
        return new ResolveCascadedPathsResponse(metaModels, resolutions);
    }

    /**
     * Reload metadata.
     * The current replica will be unavailable if an exception occurs during the reload,
     * and the metadata needs to be fixed and reloaded.
     */
    @Override
    public void reloadMetadata() {
        // Send an inner broadcast to reload the metadata of replica containers.
        InnerBroadcastMessage message = new InnerBroadcastMessage();
        message.setBroadcastType(InnerBroadcastType.RELOAD_METADATA);
        message.setContext(ContextHolder.cloneContext());
        innerBroadcastProducer.sendInnerBroadcast(message);
    }

    /**
     * Export runtime metadata rows for the given studio-managed model, scoped to
     * the requested app identity.
     * <p>
     * Scope is {@code appCode} ONLY: studio is the complete source of truth for the
     * app's metadata, so the export returns the full app catalog (framework / system
     * models included). The requested {@code appCode} must match this runtime's
     * configured identity (handshake).
     * <p>
     * Main entities carry {@code appCode} directly — filter on that. Translation
     * entities (suffix {@code Trans}) do not; the parent model does, so a two-step
     * query resolves the parent row ids for this app and filters the Trans rows by
     * {@code rowId}.
     */
    @Override
    public List<Map<String, Object>> exportRuntimeMetadata(String modelName, String appCode,
                                                           String keyColumn, Collection<String> keyValues) {
        Assert.notBlank(modelName, "Model name cannot be empty.");
        Assert.notBlank(appCode, "App code cannot be empty.");
        String configured = MetadataAppIdentity.configured();
        Assert.isEqual(configured, appCode,
                "Requested appCode {0} does not match this runtime's system.app-code {1}; "
                        + "the export was addressed to a different app.",
                appCode, configured);
        Filters filters = buildAppScopedFilter(modelName, appCode);
        if (filters == null) {
            return List.of();
        }
        // Incremental fetch: narrow to the requested aggregate business keys. An empty
        // key set is "fetch nothing" (the studio has no aggregate of this root to pull) — distinct from
        // a null keyColumn, which is the full app-scoped export.
        if (keyColumn != null) {
            if (keyValues == null || keyValues.isEmpty()) {
                return List.of();
            }
            Assert.isTrue(ModelManager.existField(modelName, keyColumn),
                    "Runtime model {0} has no column {1} to narrow the export by.", modelName, keyColumn);
            filters.in(keyColumn, keyValues);
        }
        FlexQuery flexQuery = new FlexQuery(ModelManager.getModelFieldsWithoutXToMany(modelName), filters);
        return modelService.searchList(modelName, flexQuery);
    }

    @Override
    public RuntimeChecksumsDTO exportRuntimeChecksums(String appCode) {
        // Both lanes are code-linked on the runtime side: a child's modelName / optionSetCode
        // matches its parent's. exportRuntimeMetadata enforces the appCode handshake and returns
        // the FULL app catalog: studio is the complete source
        // of truth for the app's metadata (framework / system models included), so the desired-state
        // diff converges the whole catalog and a runtime-only aggregate is a genuinely removed one.
        String byModelName = LambdaUtils.getAttributeName(SysField::getModelName);
        String byOptionSetCode = LambdaUtils.getAttributeName(SysOptionItem::getOptionSetCode);
        Map<String, String> models = AggregateChecksumIndex.models(
                exportRuntimeMetadata(SysModel.class.getSimpleName(), appCode),
                exportRuntimeMetadata(SysField.class.getSimpleName(), appCode),
                exportRuntimeMetadata(SysModelIndex.class.getSimpleName(), appCode),
                byModelName, byModelName);
        Map<String, String> optionSets = AggregateChecksumIndex.optionSets(
                exportRuntimeMetadata(SysOptionSet.class.getSimpleName(), appCode),
                exportRuntimeMetadata(SysOptionItem.class.getSimpleName(), appCode),
                byOptionSetCode, byOptionSetCode);
        return new RuntimeChecksumsDTO(models, optionSets);
    }

    /**
     * Build an app-scoped {@link Filters} for the runtime model: {@code appCode}
     * attribution only. Studio is the complete source of truth for the app's
     * metadata, so the export returns the full catalog.
     * <p>
     * Returns {@code null} when the scope resolves to "no possible rows" (e.g. a Trans
     * model whose parent has no rows for this app) so the caller can short-circuit
     * instead of issuing a query that the filter layer would reject for being empty.
     */
    private Filters buildAppScopedFilter(String modelName, String appCode) {
        if (!modelName.endsWith(ModelConstant.MODEL_TRANS_SUFFIX)) {
            Assert.isTrue(ModelManager.existField(modelName, MetadataAppIdentity.APP_CODE_FIELD),
                    "Runtime model {0} has no appCode column and is not a translation model; cannot scope by app.",
                    modelName);
            return scopedFilter(appCode);
        } else {
            String businessModel = modelName.substring(0, modelName.length() - ModelConstant.MODEL_TRANS_SUFFIX.length());
            Assert.isTrue(ModelManager.existField(businessModel, MetadataAppIdentity.APP_CODE_FIELD),
                    "Business model {0} must carry appCode to scope its translations.", businessModel);
            FlexQuery flexQuery = new FlexQuery(List.of(ModelConstant.ID), scopedFilter(appCode));
            List<Map<String, Object>> rows = modelService.searchList(businessModel, flexQuery);
            List<Serializable> businessIds = rows.stream()
                    .map(row -> (Serializable) row.get(ModelConstant.ID))
                    .toList();
            if (businessIds.isEmpty()) {
                return null;
            }
            return new Filters().in("rowId", businessIds);
        }
    }

    private Filters scopedFilter(String appCode) {
        // appCode (app identity) is the ONLY export scope: studio is the COMPLETE source of truth
        // for the app's metadata — framework / system models are maintained through studio too —
        // so the export returns the full catalog and the desired-state diff converges it (a
        // runtime-only aggregate is genuinely removed).
        return new Filters().eq(MetadataAppIdentity.APP_CODE_FIELD, appCode);
    }
}
