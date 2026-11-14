package io.softa.starter.message.mail.controller;

import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import io.softa.framework.orm.annotation.DataMask;
import io.softa.framework.orm.entity.AbstractModel;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.service.MailServerService;

/**
 * The write endpoints every mail server page shares — a tenant's or the platform's, send or receive.
 * <p>
 * The single-row writes shadow the generic {@code /{modelName}/...} routes (a literal path is more
 * specific), so the standard admin UI writes flow through here: after a row is saved with
 * {@code isDefault = true}, every other default of the same owner is demoted, keeping at most one
 * default per owner. Batch and copy endpoints are not shadowed; rows written through them fall back
 * to the {@code sequence} tie-break. Deletes are the generic endpoint's; the config cache is dropped
 * after every committed write by {@link io.softa.starter.message.shared.ConfigCacheEvictor}.
 */
public abstract class AbstractMailServerController<S extends MailServerService<T>, T extends AbstractModel>
        extends EntityController<S, T, Long> {

    @Autowired
    private ModelService<Long> modelService;

    /** The model this controller writes. */
    protected abstract String modelName();

    @Operation(summary = "Create a server — marking it default demotes the owner's other defaults")
    @PostMapping("/createOne")
    @DataMask
    @Transactional
    public ApiResponse<Long> createOne(@RequestBody Map<String, Object> row) {
        Long id = modelService.createOne(modelName(), row);
        if (RowValues.isTrue(row, "isDefault")) {
            service.demoteOtherDefaults(id);
        }
        return ApiResponse.success(id);
    }

    @Operation(summary = "Create a server and fetch — marking it default demotes the owner's other defaults")
    @PostMapping("/createOneAndFetch")
    @DataMask
    @Transactional
    public ApiResponse<Map<String, Object>> createOneAndFetch(@RequestBody Map<String, Object> row) {
        Map<String, Object> created = modelService.createOneAndFetch(modelName(), row, ConvertType.REFERENCE);
        if (RowValues.isTrue(row, "isDefault")) {
            service.demoteOtherDefaults(RowValues.id(created));
        }
        return ApiResponse.success(created);
    }

    @Operation(summary = "Update a server — marking it default demotes the owner's other defaults")
    @PostMapping("/updateOne")
    @DataMask
    @Transactional
    public ApiResponse<Boolean> updateOne(@RequestBody Map<String, Object> row) {
        boolean updated = modelService.updateOne(modelName(), row);
        if (RowValues.isTrue(row, "isDefault")) {
            service.demoteOtherDefaults(RowValues.id(row));
        }
        return ApiResponse.success(updated);
    }

    @Operation(summary = "Update a server and fetch — marking it default demotes the owner's other defaults")
    @PostMapping("/updateOneAndFetch")
    @DataMask
    @Transactional
    public ApiResponse<Map<String, Object>> updateOneAndFetch(@RequestBody Map<String, Object> row) {
        Map<String, Object> updated = modelService.updateOneAndFetch(modelName(), row, ConvertType.REFERENCE);
        if (RowValues.isTrue(row, "isDefault")) {
            service.demoteOtherDefaults(RowValues.id(row));
        }
        return ApiResponse.success(updated);
    }

    @Operation(summary = "Test connectivity",
            description = "Verify that the system can connect to and authenticate with the mail server.")
    @PostMapping("/testConnectivity")
    public ApiResponse<ConnectivityTestResultDTO> testConnectivity(@RequestParam Long id) {
        return ApiResponse.success(service.testConnectivity(id));
    }
}
