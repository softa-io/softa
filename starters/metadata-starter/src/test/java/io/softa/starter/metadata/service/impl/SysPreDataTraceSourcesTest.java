package io.softa.starter.metadata.service.impl;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.entity.SysPreData;
import io.softa.starter.metadata.seed.TenantSeedFileResult;
import io.softa.starter.metadata.service.SysPreDataService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tracing bindings written before they recorded their file: each takes the file declaring its row, a retired
 * preId takes the file that retired it, and one no file declares is marked untraced — and listed.
 */
class SysPreDataTraceSourcesTest {

    private static final Long TENANT = 7L;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void everyBindingIsStampedWithItsFileOrMarkedUntraced() {
        SysPreDataServiceImpl service = spy(new SysPreDataServiceImpl((ModelService) mock(ModelService.class)));
        doReturn(List.of(binding(1L, "Role", "role.admin"), binding(2L, "RoleNavigation", "nav.admin.home"),
                binding(3L, "Role", "role.old"), binding(4L, "ImportTemplateField", "gone.01")))
                .when(service).searchList(any(Filters.class));
        doReturn(true).when(service).updateList(any(List.class));

        List<TenantSeedFileResult> results = service.traceSources(Map.of(
                "Role/role.admin", "Role.Tenant.json",
                "RoleNavigation/nav.admin.home", "Role.Tenant.json",
                "*/role.old", "Role.Tenant.json"), TENANT);

        assertThat(results).extracting(TenantSeedFileResult::file)
                .containsExactly("Role.Tenant.json", SysPreDataService.UNTRACED_SOURCE);
        assertThat(results.get(0).traced()).isEqualTo(3);
        assertThat(results.get(1).traced()).isEqualTo(1);
        assertThat(results.get(1).notes()).containsExactly("ImportTemplateField/gone.01");

        ArgumentCaptor<List<SysPreData>> patches = ArgumentCaptor.forClass(List.class);
        verify(service, times(2)).updateList(patches.capture());
        assertThat(patches.getAllValues().get(0)).extracting(SysPreData::getSourceFile).containsOnly("Role.Tenant.json");
        assertThat(patches.getAllValues().get(1)).extracting(SysPreData::getId).containsExactly(4L);
    }

    private static SysPreData binding(Long id, String model, String preId) {
        SysPreData binding = new SysPreData();
        binding.setId(id);
        binding.setTenantId(TENANT);
        binding.setModel(model);
        binding.setPreId(preId);
        return binding;
    }
}
