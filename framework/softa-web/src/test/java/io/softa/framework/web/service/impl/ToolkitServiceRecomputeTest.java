package io.softa.framework.web.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.utils.RecomputeUtils;

class ToolkitServiceRecomputeTest {

    private static final String MODEL = "Employee";

    private final ToolkitServiceImpl toolkitService = new ToolkitServiceImpl();

    @SuppressWarnings("rawtypes")
    private final ModelService modelService = Mockito.mock(ModelService.class);
    private MockedStatic<ModelManager> modelManager;
    private MockedStatic<RecomputeUtils> recomputeUtils;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(toolkitService, "modelService", modelService);
        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.isTimelineModel(MODEL)).thenReturn(false);
        recomputeUtils = mockStatic(RecomputeUtils.class);
        recomputeUtils.when(() -> RecomputeUtils.getDependedFields(eq(MODEL), any()))
                .thenAnswer(invocation -> new HashSet<>(Set.of("deptId")));
    }

    @AfterEach
    void tearDown() {
        recomputeUtils.close();
        modelManager.close();
    }

    /**
     * Every page is written back once, and the pass ends at the first page that is not full. It used to
     * read and rewrite the first page forever on a model of 1,000 rows or more.
     */
    @Test
    @SuppressWarnings("unchecked")
    void recompute_writesBackEveryPageOnce() {
        int pageSize = BaseConstant.DEFAULT_BATCH_SIZE;
        List<List<Map<String, Object>>> pages = List.of(rows(0, pageSize), rows(pageSize, 1));
        AtomicInteger reads = new AtomicInteger();
        when(modelService.searchPage(eq(MODEL), any(), any())).thenAnswer(invocation -> {
            if (reads.incrementAndGet() > pages.size()) {
                throw new AssertionError("Read " + reads + " pages, but there are only " + pages.size());
            }
            Page<Map<String, Object>> page = invocation.getArgument(2);
            return page.setRows(pages.get(page.getPageNumber() - 1));
        });

        toolkitService.recompute(MODEL, Set.of("deptName"));

        ArgumentCaptor<List<Map<String, Object>>> written = ArgumentCaptor.forClass(List.class);
        verify(modelService, times(2)).updateList(eq(MODEL), written.capture());
        assertThat(written.getAllValues()).containsExactly(pages.get(0), pages.get(1));
    }

    private static List<Map<String, Object>> rows(int from, int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long id = from + 1; id <= from + count; id++) {
            rows.add(Map.of("id", id, "deptId", 7L));
        }
        return rows;
    }
}
