package io.softa.starter.file.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import org.springframework.util.CollectionUtils;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.dto.CountParams;
import io.softa.framework.web.dto.CountResult;
import io.softa.framework.web.dto.QueryParams;
import io.softa.framework.web.dto.SearchListParams;
import io.softa.framework.web.dto.SearchNameParams;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.orm.annotation.DataMask;
import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.entity.ImportTemplateField;
import io.softa.starter.file.excel.imports.ImportFieldAccess;
import io.softa.starter.file.support.ImportTemplateCountryScope;
import io.softa.starter.file.support.TemplateScope;
import io.softa.starter.file.service.ImportService;
import io.softa.starter.file.service.ImportTemplateService;

/**
 * ImportTemplateController
 */
@Tag(name = "Import Template")
@RestController
@RequestMapping("/ImportTemplate")
public class ImportTemplateController extends EntityController<ImportTemplateService, ImportTemplate, Long> {

    private static final String MODEL = ImportTemplate.class.getSimpleName();

    @Autowired
    private ImportService importService;

    @Autowired
    private ModelService<Long> modelService;

    @Autowired
    private ImportFieldAccess importFieldAccess;

    /**
     * Typed shadow of the generic {@code /ImportTemplate/searchPage}, narrowed to the caller's
     * countries the same way {@link #listByModel} is.
     *
     * <p>The template list is a configuration screen, and a configuration screen shows what the caller
     * may configure: their own countries' templates plus the ones that apply everywhere. The model is
     * not on the country axis — a template with no country is the common case and must stay visible —
     * so the ORM's own narrowing leaves it alone, and the generic endpoint would list every country's
     * templates to everyone. Spring routes here over the templated {@code /{modelName}/searchPage}
     * because the literal path is more specific.
     */
    @Operation(summary = "searchPage", description = "Page of import templates, narrowed to the caller's countries "
            + "plus the templates that apply to every country.")
    @PostMapping("/searchPage")
    @DataMask
    public ApiResponse<Page<Map<String, Object>>> searchPage(@RequestBody(required = false) QueryParams queryParams) {
        if (queryParams == null) {
            queryParams = new QueryParams();
        }
        FlexQuery flexQuery = QueryParams.convertParamsToFlexQuery(queryParams);
        flexQuery.setFilters(withCountryScope(flexQuery.getFilters()));
        Page<Map<String, Object>> page = Page.of(queryParams.getPageNumber(), queryParams.getPageSize());
        return ApiResponse.success(modelService.searchPage(MODEL, flexQuery, page));
    }

    /** Typed shadow of the generic {@code /ImportTemplate/searchList} — same narrowing as {@link #searchPage}. */
    @Operation(summary = "searchList", description = "List of import templates, narrowed to the caller's countries "
            + "plus the templates that apply to every country.")
    @PostMapping("/searchList")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> searchList(@RequestBody(required = false) SearchListParams searchListParams) {
        FlexQuery flexQuery = SearchListParams.convertParamsToFlexQuery(searchListParams);
        flexQuery.setFilters(withCountryScope(flexQuery.getFilters()));
        return ApiResponse.success(modelService.searchList(MODEL, flexQuery));
    }

    /**
     * Typed shadow of the generic {@code /ImportTemplate/searchName} — the reference picker behind a
     * "Template" filter or field (an import history row names its template). Same narrowing as the
     * list, so the picker offers only what the list shows.
     */
    @Operation(summary = "searchName", description = "Import templates by display name, narrowed to the caller's "
            + "countries plus the templates that apply to every country.")
    @PostMapping("/searchName")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> searchName(@RequestBody(required = false) SearchNameParams searchNameParams) {
        FlexQuery flexQuery = SearchNameParams.convertParamsToFlexQuery(searchNameParams);
        flexQuery.setFilters(withCountryScope(flexQuery.getFilters()));
        return ApiResponse.success(modelService.searchName(MODEL, flexQuery));
    }

    /** Typed shadow of the generic {@code /ImportTemplate/count} — the total a narrowed page reports. */
    @Operation(summary = "count", description = "Count of import templates, narrowed like the list.")
    @PostMapping("/count")
    @DataMask
    public ApiResponse<CountResult> count(@RequestBody(required = false) CountParams countParams) {
        if (countParams == null) {
            countParams = new CountParams();
        }
        ContextHolder.getContext().setEffectiveDate(countParams.getEffectiveDate());
        Filters filters = withCountryScope(countParams.getFilters());
        CountResult result = new CountResult();
        List<String> groupBy = countParams.getGroupBy();
        if (!CollectionUtils.isEmpty(groupBy)) {
            Assert.allNotBlank(groupBy, "`groupBy` cannot contain empty value: {0}", groupBy);
            FlexQuery flexQuery = new FlexQuery(filters, countParams.getOrders());
            flexQuery.setFields(new HashSet<>(groupBy));
            flexQuery.setGroupBy(groupBy);
            flexQuery.setConvertType(ConvertType.TYPE_CAST);
            result.setGroups(modelService.searchList(MODEL, flexQuery));
        } else {
            result.setTotal(modelService.count(MODEL, filters));
        }
        return ApiResponse.success(result);
    }

    /** The caller's filters AND the country scope; the filters alone when there is nothing to narrow by. */
    Filters withCountryScope(Filters filters) {
        return ImportTemplateCountryScope.withCountryScope(filters);
    }

    /**
     * List the import templates of the specified model the caller may use, in display order.
     *
     * <p>Narrowed to the caller's countries plus the templates with no country (see
     * {@link ImportTemplateCountryScope}), and ordered by {@code sequence} ascending — a template with
     * no sequence after those with one — then by id, so the order is the same on every database.
     *
     * @param modelName model name
     * @return list of import templates
     */
    @Operation(summary="listByModel", description = "List the import templates of the specified model, "
            + "narrowed to the caller's countries plus the templates that apply to every country, "
            + "ordered by sequence (unset last), then id.")
    @PostMapping(value = "/listByModel")
    public ApiResponse<List<ImportTemplate>> listByModel(@RequestParam String modelName) {
        Set<String> modelNames = TemplateScope.of(modelName, this::standaloneModelNames);
        Filters filters = new Filters().in(ImportTemplate::getModelName, modelNames);
        Filters countryScope = countryScope();
        if (countryScope != null) {
            filters.and(countryScope);
        }
        FlexQuery flexQuery = new FlexQuery(filters).expandSubQuery(ImportTemplate::getImportFields);
        List<ImportTemplate> templates = inDisplayOrder(service.searchList(flexQuery));
        templates.forEach(this::withoutUnwritableFields);
        return ApiResponse.success(templates);
    }

    /**
     * Leave out the columns the caller may not write, as the downloaded template does — so the dialog
     * does not list a column the file will not have, nor call one required that the caller cannot fill.
     */
    void withoutUnwritableFields(ImportTemplate template) {
        List<ImportTemplateField> fields = template.getImportFields();
        if (CollectionUtils.isEmpty(fields)) {
            return;
        }
        Set<String> withheld = importFieldAccess.withheld(template.getModelName(), template.getImportRule(),
                fields.stream().map(ImportTemplateField::getFieldName).toList());
        if (!withheld.isEmpty()) {
            template.setImportFields(fields.stream().filter(f -> !withheld.contains(f.getFieldName())).toList());
        }
    }

    /** Sequence ascending with unset last, then id. Sorted here: databases disagree on where nulls go. */
    static final Comparator<ImportTemplate> DISPLAY_ORDER =
            Comparator.comparing(ImportTemplate::getSequence, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ImportTemplate::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    static List<ImportTemplate> inDisplayOrder(List<ImportTemplate> templates) {
        List<ImportTemplate> sorted = new ArrayList<>(templates);
        sorted.sort(DISPLAY_ORDER);
        return sorted;
    }

    /** See {@link ImportTemplateCountryScope}: the same rule every template listing states. */
    Filters countryScope() {
        return ImportTemplateCountryScope.countryScope();
    }

    /**
     * Get the fileInfo of the import template by template ID.
     * The fileInfo contains the download URL.
     *
     * @param id template ID
     * @return import template fileInfo
     */
    @Operation(description = """
            Get the fileInfo of the import template by template ID.
            The fileInfo contains the download URL.""")
    @GetMapping("/getTemplateFile")
    public ApiResponse<FileInfo> getTemplateFile(@RequestParam(name = "id") Long id) {
        return ApiResponse.success(importService.getTemplateFile(id));
    }


    /** Models whose import templates belong only on their own page — see {@link TemplateScope}. */
    private Set<String> standaloneModelNames() {
        Filters standalone = new Filters().eq(ImportTemplate::getStandalone, true);
        return service.searchList(new FlexQuery(standalone)).stream()
                .map(ImportTemplate::getModelName)
                .collect(Collectors.toSet());
    }
}
