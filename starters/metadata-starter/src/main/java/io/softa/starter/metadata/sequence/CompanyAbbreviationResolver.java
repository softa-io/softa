package io.softa.starter.metadata.sequence;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;

/**
 * Finds the company abbreviation for each record a sequence is about to number.
 *
 * <p>Asks the {@link CompanyCascadePathResolver}s, in order, how the model reaches its company, and
 * follows that path: the first hop's id is read from each record, and each further hop is one lookup
 * for the whole batch ({@code id IN …}, distinct ids only) — never one query per record. The longest
 * path in use is two hops, so numbering a thousand child records costs two queries.
 *
 * <p>The answer is empty — and the code is generated without a prefix — whenever there is nothing to
 * render: no company model here, a company without an abbreviation, a model with no path to a company,
 * or a link missing along the way. An abbreviation is optional; its absence is never an error.
 *
 * <p>Lookups skip permission checks and the active / soft-delete filters. Numbering is a system act:
 * the prefix must not depend on what the person saving the record may see, nor disappear because the
 * company was deactivated. Tenant isolation still applies.
 */
@Slf4j
@Component
public class CompanyAbbreviationResolver {

    static final String ABBREVIATION_FIELD = "abbreviation";
    private static final String NAME_FIELD = "name";
    private static final String ACTIVE_FIELD = "active";
    /** A blank abbreviation passes {@code IS SET}; a few candidates leave room to skip one. */
    private static final int SAMPLE_CANDIDATES = 20;

    /** Lookups are chunked so an {@code IN} list stays within what every supported database accepts. */
    private static final int LOOKUP_CHUNK = 500;

    private final List<CompanyCascadePathResolver> resolvers;
    private final ModelService<Serializable> modelService;
    private final ConcurrentMap<String, Optional<String>> paths = new ConcurrentHashMap<>();

    /** {@code resolvers} arrive sorted by {@code @Order}; {@code modelService} is lazy to stay out of its init cycle. */
    public CompanyAbbreviationResolver(List<CompanyCascadePathResolver> resolvers,
                                       @Lazy ModelService<Serializable> modelService) {
        this.resolvers = List.copyOf(resolvers);
        this.modelService = modelService;
    }

    /**
     * @param modelName the model being numbered
     * @param rows      the records about to be inserted
     * @return one abbreviation per row, aligned with {@code rows}; {@code null} where there is none
     */
    public List<String> abbreviationsOf(String modelName, List<Map<String, Object>> rows) {
        List<String> none = Collections.nCopies(rows.size(), null);
        if (rows.isEmpty() || !companyHasAbbreviation()) {
            return none;
        }
        if (!ContextHolder.existContext()) {
            // No request or job context: getContext() would hand back a fresh one with no tenant, and
            // a lookup under it could answer from another tenant's companies. Render no prefix instead.
            return none;
        }
        Optional<String> path = pathOf(modelName);
        if (path.isEmpty()) {
            return none;
        }
        if (CompanyCascadePathResolver.SELF_PATH.equals(path.get())) {
            return rows.stream().map(row -> text(row.get(ABBREVIATION_FIELD))).toList();
        }

        String[] hops = path.get().split("\\.");
        List<Object> ids = rows.stream().map(row -> row.get(hops[0])).toList();
        String model = modelName;
        for (int i = 1; i < hops.length; i++) {
            MetaField via = ModelManager.getModelFieldOrNull(model, hops[i - 1]);
            if (via == null || StringUtils.isBlank(via.getRelatedModel())) {
                log.warn("Company path {} of model {} breaks at {}; no abbreviation rendered",
                        path.get(), modelName, hops[i - 1]);
                return none;
            }
            model = via.getRelatedModel();
            if (ModelManager.getModelFieldOrNull(model, hops[i]) == null) {
                // A wrong path is an application bug; it costs the prefix, not the insert.
                log.warn("Company path {} of model {} names {}.{}, which does not exist; no abbreviation rendered",
                        path.get(), modelName, model, hops[i]);
                return none;
            }
            ids = follow(model, hops[i], ids);
        }
        return follow(ModelConstant.COMPANY_MODEL, ABBREVIATION_FIELD, ids).stream()
                .map(CompanyAbbreviationResolver::text)
                .toList();
    }

    /**
     * What {@code {companyAbbr}} renders for a model, shown by a template preview: whether the model
     * has a company at all — the same path lookup numbering uses, so the preview cannot disagree with
     * what is generated — and a sample company to render it with: the newest active company that has
     * an abbreviation.
     *
     * <p>The sample is read without permission checks, like the numbering itself: someone allowed to
     * edit a numbering rule sees how it renders whether or not they may read companies, and only a
     * company's name and abbreviation are returned.
     *
     * @param modelName the model the sequence numbers
     */
    public CompanyAbbrPreview previewFor(String modelName) {
        if (!companyHasAbbreviation() || pathOf(modelName).isEmpty()) {
            return CompanyAbbrPreview.notApplicable();
        }
        FlexQuery query = new FlexQuery(List.of(NAME_FIELD, ABBREVIATION_FIELD),
                new Filters().eq(ACTIVE_FIELD, true).isSet(ABBREVIATION_FIELD));
        query.setOrders(Orders.ofDesc(ModelConstant.ID));
        query.setLimitSize(SAMPLE_CANDIDATES);
        Context system = ContextHolder.getContext().copy();
        system.setSkipPermissionCheck(true);
        List<Map<String, Object>> companies =
                ContextHolder.callWith(system, () -> modelService.searchList(ModelConstant.COMPANY_MODEL, query));
        for (Map<String, Object> company : companies) {
            String abbreviation = text(company.get(ABBREVIATION_FIELD));
            if (abbreviation != null) {
                return new CompanyAbbrPreview(true, text(company.get(NAME_FIELD)), abbreviation);
            }
        }
        return new CompanyAbbrPreview(true, null, null);
    }

    /** The first non-empty answer of the ordered resolvers, cached per model — it never changes for a model. */
    Optional<String> pathOf(String modelName) {
        return paths.computeIfAbsent(modelName, name -> resolvers.stream()
                .map(resolver -> resolver.resolve(name))
                .flatMap(Optional::stream)
                .findFirst());
    }

    private static boolean companyHasAbbreviation() {
        return ModelManager.existModel(ModelConstant.COMPANY_MODEL)
                && ModelManager.getModelFieldOrNull(ModelConstant.COMPANY_MODEL, ABBREVIATION_FIELD) != null;
    }

    /** Replace each id by the value of {@code field} on the {@code model} row it names; null stays null. */
    private List<Object> follow(String model, String field, List<Object> ids) {
        Set<Object> distinct = new LinkedHashSet<>();
        for (Object id : ids) {
            if (id != null && !(id instanceof CharSequence cs && cs.isEmpty())) {
                distinct.add(id);
            }
        }
        if (distinct.isEmpty()) {
            return Collections.nCopies(ids.size(), null);
        }
        Map<String, Object> valueById = new HashMap<>();
        List<Object> pending = new ArrayList<>(distinct);
        for (int from = 0; from < pending.size(); from += LOOKUP_CHUNK) {
            List<Object> chunk = pending.subList(from, Math.min(from + LOOKUP_CHUNK, pending.size()));
            for (Map<String, Object> row : read(model, field, chunk)) {
                valueById.put(String.valueOf(row.get(ModelConstant.ID)), row.get(field));
            }
        }
        List<Object> out = new ArrayList<>(ids.size());
        for (Object id : ids) {
            out.add(id == null ? null : valueById.get(String.valueOf(id)));
        }
        return out;
    }

    private List<Map<String, Object>> read(String model, String field, List<Object> ids) {
        FlexQuery query = new FlexQuery(List.of(ModelConstant.ID, field), Filters.of(ModelConstant.ID, Operator.IN, ids));
        query.setFilterControl(FilterControl.bypassAll());
        Context system = ContextHolder.getContext().copy();
        system.setSkipPermissionCheck(true);
        return ContextHolder.callWith(system, () -> modelService.searchList(model, query));
    }

    /**
     * Trimmed: a company being created is numbered before its own fields are trimmed, so its
     * abbreviation arrives here as typed.
     */
    private static String text(Object value) {
        return value instanceof CharSequence cs && !StringUtils.isBlank(cs) ? cs.toString().trim() : null;
    }
}
