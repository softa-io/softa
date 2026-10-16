package io.softa.framework.orm.utils;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.*;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.SpringContextUtils;
import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.entity.AbstractModel;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.service.EntityService;
import io.softa.framework.orm.service.FileService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.impl.ModelServiceImpl;

/**
 * Reflectively call the methods of the Model Service
 */
public abstract class ReflectTool {

    private ReflectTool() {}

    /**
     * Get the `modelService`
     * @return modelService
     */
    @SuppressWarnings("unchecked")
    private static <K extends Serializable> ModelService<K> getModelService() {
        return SpringContextUtils.getBeanByClass(ModelServiceImpl.class);
    }

    /**
     * Get the entity service of specified model
     * @param modelName model name
     * @return entityService
     */
    private static <T extends AbstractModel, K extends Serializable> EntityService<T, K> getEntityService(String modelName) {
        return SpringContextUtils.getBeanByName(StringTools.lowerCaseFirstLetter(modelName + "ServiceImpl"));
    }

    /**
     * Get the ids of the specified model.
     *
     * @param modelName model name
     * @param filters filters
     * @return ids list
     */
    public static List<Serializable> getIds(String modelName, Filters filters) {
        return getModelService().getIds(modelName, filters);
    }

    /**
     * Get up to {@code limitSize} ids of the specified model matching the filters (a LIMIT-ed getIds) —
     * lets a caller detect an over-limit set (fetch limit+1) without loading the full list.
     *
     * @param modelName model name
     * @param filters filters
     * @param limitSize max number of ids to return
     * @return up to {@code limitSize} matching ids
     */
    public static List<Serializable> getIds(String modelName, Filters filters, int limitSize) {
        return getModelService().getIds(modelName, filters, limitSize);
    }

    /**
     * Count rows of the specified model matching the filters.
     *
     * @param modelName model name
     * @param filters filters
     * @return matching row count
     */
    public static long count(String modelName, Filters filters) {
        return getModelService().count(modelName, filters);
    }

    /**
     * Get the ids for ManyToOne/OneToOne relational field.
     *
     * @param modelName model name
     * @param filters filters
     * @param fieldName relational field name
     * @return distinct ids for relational field
     */
    public static List<Serializable> getRelatedIds(String modelName, String fieldName, Filters filters) {
        return getModelService().getRelatedIds(modelName, filters, fieldName);
    }

    /**
     * Batch get the displayNames of the specified ids of the current model: {id: displayName}.
     *
     * @param modelName model name
     * @param ids id list
     * @return displayNames Map
     */
    public static Map<Serializable, String> getDisplayNames(String modelName, List<Serializable> ids) {
        return getModelService().getDisplayNames(modelName, ids);
    }

    /**
     * Get display names for the specified filters, returning a map of {id ->
     * displayName}.
     *
     * @param modelName the name of the model
     * @param filters   the filters to apply
     * @return a map of IDs to their resolved display names
     */
    public static Map<Serializable, String> getDisplayNames(String modelName, Filters filters) {
        return getModelService().getDisplayNames(modelName, filters);
    }

    /**
     * Create data list
     *
     * @param rows data list
     * @return enhanced List<Map> data list
     */
    public static List<Serializable> createList(String modelName, List<Map<String, Object>> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return Collections.emptyList();
        }
        return getModelService().createList(modelName, rows);
    }

    /**
     * Batch update data according to the id field in the list
     *
     * @param rows data list
     * @return enhanced List<Map> data list
     */
    public static boolean updateList(String modelName, List<Map<String, Object>> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return false;
        }
        return getModelService().updateList(modelName, rows);
    }

    /**
     * Call the getByIds method of the specified model
     *
     * @param modelName model name
     * @param ids id list
     * @param fields field list to read
     * @param subQueries subQueries object
     * @param convertType format type
     * @return List<Map> data list
     */
    public static List<Map<String, Object>> getByIds(String modelName, List<Serializable> ids, Set<String> fields,
                                                     SubQueries subQueries, ConvertType convertType) {
        if (CollectionUtils.isEmpty(ids)) {
            return new ArrayList<>();
        }
        return getModelService().getByIds(modelName, ids, fields, subQueries, convertType);
    }

    /**
     * Call the searchList method of the specified model
     *
     * @param modelName model name
     * @param flexQuery flexQuery
     * @return Search result
     */
    public static List<Map<String, Object>> searchList(String modelName, FlexQuery flexQuery) {
        return getModelService().searchList(modelName, flexQuery);
    }

    /**
     * Call the searchListIgnoringRowScope method of the specified model — the caller's row range is
     * not appended, the field guards still are.
     *
     * <p>For the read pipeline's relation processors, which reach rows a caller was shown a reference
     * to rather than rows they searched for. See
     * {@link io.softa.framework.orm.service.ModelService#searchListIgnoringRowScope} for why this is a
     * method and not a flag.
     *
     * @param modelName model name
     * @param flexQuery flexQuery
     * @return Search result, row range not applied
     */
    public static List<Map<String, Object>> searchListIgnoringRowScope(String modelName, FlexQuery flexQuery) {
        return getModelService().searchListIgnoringRowScope(modelName, flexQuery);
    }

    /**
     * Call the searchName method of the specified model
     *
     * @param modelName model name
     * @param flexQuery flexQuery
     * @return Search result
     */
    public static List<Map<String, Object>> searchName(String modelName, FlexQuery flexQuery) {
        return getModelService().searchName(modelName, flexQuery);
    }

    /**
     * Call the searchNameIgnoringRowScope method of the specified model — the caller's row range is
     * not appended, the field guards still are.
     *
     * <p>For the read pipeline's relation processors; see
     * {@link io.softa.framework.orm.service.ModelService#searchNameIgnoringRowScope}.
     *
     * @param modelName model name
     * @param flexQuery flexQuery
     * @return Search result, row range not applied
     */
    public static List<Map<String, Object>> searchNameIgnoringRowScope(String modelName, FlexQuery flexQuery) {
        return getModelService().searchNameIgnoringRowScope(modelName, flexQuery);
    }

    /**
     * Call the searchPage method of the specified model
     *
     * @param modelName model name
     * @param flexQuery flexQuery
     * @param page page object
     * @return Search result
     */
    public static Page<Map<String, Object>> searchPage(String modelName, FlexQuery flexQuery, Page<Map<String, Object>> page) {
        return getModelService().searchPage(modelName, flexQuery, page);
    }

    /**
     * Call the delete method of the specified model
     *
     * @param modelName model name
     * @param ids id list
     * @return delete result
     */
    public static boolean deleteList(String modelName, List<Serializable> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return false;
        }
        return getModelService().deleteByIds(modelName, ids);
    }

    /**
     * Call the service method of the specified model through reflection
     *
     * @param modelName model name
     * @param methodName method name
     * @param params method parameters
     * @return method execution result
     */
    public static Object invoke(String modelName, String methodName, Object... params) {
        Class<?>[] paramsClass = null;
        if (params != null && params.length != 0) {
            paramsClass = new Class<?>[params.length];
            for (int i = 0; i < params.length; i++) {
                paramsClass[i] = params[i].getClass();
            }
        }
        EntityService<?, ?> entityService = getEntityService(modelName);
        Method method = ReflectionUtils.findMethod(entityService.getClass(), methodName, paramsClass);
        Assert.notNull(method, "Model {0} does not exist Method {1} with Parameter type {2}!", modelName, methodName, paramsClass);
        return ReflectionUtils.invokeMethod(method, entityService, params);
    }

    /**
     * Get the FileInfo object list by fileIds.
     *
     * @param fileIds the file IDs
     * @return the list of FileInfo objects
     */
    public static List<FileInfo> getByFileIds(List<Long> fileIds) {
        FileService fileService = SpringContextUtils.getBeanByClass(FileService.class);
        return fileService.getByFileIds(fileIds);
    }
}
