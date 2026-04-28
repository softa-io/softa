package io.softa.starter.studio.release.preview;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.enums.ChangeKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PreviewAssemblerTest {

    private PreviewAssembler assembler;
    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);

    @BeforeEach
    void setUp() {
        assembler = new PreviewAssembler();
        ReflectionTestUtils.setField(assembler, "modelService", modelService);
    }

    @Test
    void rootOnlyChangeProducesSingleRoot() {
        ModelChangesDTO modelChanges = new ModelChangesDTO("DesignModel");
        modelChanges.addUpdatedRow(row("DesignModel", 1L, AccessType.UPDATE,
                Map.of(ModelConstant.ID, 1L, "modelName", "Customer")));

        PreviewTreeDTO tree = assembler.assemble(List.of(modelChanges));

        assertEquals(1, tree.getModels().size());
        assertTrue(tree.getOptionSets().isEmpty());
        assertTrue(tree.getNavigations().isEmpty());
        PreviewNodeDTO root = tree.getModels().getFirst();
        assertEquals("DesignModel", root.getModelName());
        assertEquals(1L, root.getRowId());
        assertEquals(ChangeKind.UPDATE, root.getKind());
        assertTrue(root.getChildren().isEmpty());
        verify(modelService, never()).getByIds(any(), any(), any());
    }

    @Test
    void designFieldUnderUnchangedDesignModelSynthesizesContainerWithoutDbLookup() {
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addCreatedRow(row("DesignField", 200L, AccessType.CREATE,
                Map.of(ModelConstant.ID, 200L, "modelId", 1L, "modelName", "Customer", "fieldName", "name")));

        PreviewTreeDTO tree = assembler.assemble(List.of(fieldChanges));

        assertEquals(1, tree.getModels().size());
        PreviewNodeDTO root = tree.getModels().getFirst();
        assertEquals("DesignModel", root.getModelName());
        assertEquals(1L, root.getRowId());
        assertEquals(ChangeKind.INDIRECT, root.getKind());
        assertEquals("Customer", root.getRecord().getCurrentData().get("modelName"));

        assertEquals(1, root.getChildren().size());
        PreviewNodeDTO field = root.getChildren().getFirst();
        assertEquals("DesignField", field.getModelName());
        assertEquals(ChangeKind.CREATE, field.getKind());

        verify(modelService, never()).getByIds(any(), any(), any());
    }

    @Test
    void designFieldTransOrphanFetchesParentChainAndAttachesToDesignModelRoot() {
        ModelChangesDTO transChanges = new ModelChangesDTO("DesignFieldTrans");
        transChanges.addUpdatedRow(row("DesignFieldTrans", 5000L, AccessType.UPDATE,
                Map.of(ModelConstant.ID, 5000L, "rowId", 200L, "languageCode", "en_US", "labelName", "Name")));

        when(modelService.getByIds(eq("DesignField"), eq(List.of(200L)), any()))
                .thenReturn(List.of(Map.of(
                        ModelConstant.ID, 200L,
                        "modelId", 1L,
                        "modelName", "Customer",
                        "fieldName", "name")));

        PreviewTreeDTO tree = assembler.assemble(List.of(transChanges));

        assertEquals(1, tree.getModels().size());
        PreviewNodeDTO modelRoot = tree.getModels().getFirst();
        assertEquals("DesignModel", modelRoot.getModelName());
        assertEquals(1L, modelRoot.getRowId());
        assertEquals(ChangeKind.INDIRECT, modelRoot.getKind());
        assertEquals("Customer", modelRoot.getRecord().getCurrentData().get("modelName"));

        assertEquals(1, modelRoot.getChildren().size());
        PreviewNodeDTO fieldContainer = modelRoot.getChildren().getFirst();
        assertEquals("DesignField", fieldContainer.getModelName());
        assertEquals(200L, fieldContainer.getRowId());
        assertEquals(ChangeKind.INDIRECT, fieldContainer.getKind());
        assertEquals("name", fieldContainer.getRecord().getCurrentData().get("fieldName"));

        assertEquals(1, fieldContainer.getChildren().size());
        PreviewNodeDTO trans = fieldContainer.getChildren().getFirst();
        assertEquals("DesignFieldTrans", trans.getModelName());
        assertEquals(ChangeKind.UPDATE, trans.getKind());

        verify(modelService, times(1)).getByIds(eq("DesignField"), eq(List.of(200L)), any());
        verify(modelService, never()).getByIds(eq("DesignModel"), any(), any());
    }

    @Test
    void designOptionItemUnderUnchangedOptionSetSynthesizesContainerFromDenormalizedCode() {
        ModelChangesDTO itemChanges = new ModelChangesDTO("DesignOptionItem");
        itemChanges.addCreatedRow(row("DesignOptionItem", 7L, AccessType.CREATE,
                Map.of(ModelConstant.ID, 7L, "optionSetId", 3L, "optionSetCode", "STATUS", "itemCode", "ACTIVE")));

        PreviewTreeDTO tree = assembler.assemble(List.of(itemChanges));

        assertTrue(tree.getModels().isEmpty());
        assertEquals(1, tree.getOptionSets().size());
        PreviewNodeDTO root = tree.getOptionSets().getFirst();
        assertEquals("DesignOptionSet", root.getModelName());
        assertEquals(3L, root.getRowId());
        assertEquals(ChangeKind.INDIRECT, root.getKind());
        assertEquals("STATUS", root.getRecord().getCurrentData().get("optionSetCode"));
        assertEquals(1, root.getChildren().size());
        verify(modelService, never()).getByIds(any(), any(), any());
    }

    @Test
    void designViewUnderUnchangedNavigationFetchesNavigation() {
        ModelChangesDTO viewChanges = new ModelChangesDTO("DesignView");
        viewChanges.addCreatedRow(row("DesignView", 9L, AccessType.CREATE,
                Map.of(ModelConstant.ID, 9L, "navId", 4L, "name", "Customer List")));

        when(modelService.getByIds(eq("DesignNavigation"), eq(List.of(4L)), any()))
                .thenReturn(List.of(Map.of(ModelConstant.ID, 4L, "name", "CRM", "code", "crm")));

        PreviewTreeDTO tree = assembler.assemble(List.of(viewChanges));

        assertEquals(1, tree.getNavigations().size());
        PreviewNodeDTO nav = tree.getNavigations().getFirst();
        assertEquals("DesignNavigation", nav.getModelName());
        assertEquals(4L, nav.getRowId());
        assertEquals(ChangeKind.INDIRECT, nav.getKind());
        assertEquals("CRM", nav.getRecord().getCurrentData().get("name"));
        assertEquals(1, nav.getChildren().size());
    }

    @Test
    void rootChildAndTransAllChangedDoNotProduceDuplicateContainers() {
        ModelChangesDTO modelChanges = new ModelChangesDTO("DesignModel");
        modelChanges.addUpdatedRow(row("DesignModel", 1L, AccessType.UPDATE,
                Map.of(ModelConstant.ID, 1L, "modelName", "Customer")));

        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addUpdatedRow(row("DesignField", 200L, AccessType.UPDATE,
                Map.of(ModelConstant.ID, 200L, "modelId", 1L, "modelName", "Customer", "fieldName", "name")));

        ModelChangesDTO transChanges = new ModelChangesDTO("DesignFieldTrans");
        transChanges.addUpdatedRow(row("DesignFieldTrans", 5000L, AccessType.UPDATE,
                Map.of(ModelConstant.ID, 5000L, "rowId", 200L, "labelName", "Name")));

        PreviewTreeDTO tree = assembler.assemble(List.of(modelChanges, fieldChanges, transChanges));

        assertEquals(1, tree.getModels().size());
        PreviewNodeDTO root = tree.getModels().getFirst();
        assertEquals(ChangeKind.UPDATE, root.getKind());
        assertEquals(1, root.getChildren().size());
        PreviewNodeDTO field = root.getChildren().getFirst();
        assertEquals(ChangeKind.UPDATE, field.getKind());
        assertEquals(1, field.getChildren().size());
        PreviewNodeDTO trans = field.getChildren().getFirst();
        assertEquals(ChangeKind.UPDATE, trans.getKind());
        verify(modelService, never()).getByIds(any(), any(), any());
    }

    @Test
    void deletedRowReadsParentFkFromDataBeforeChangeWhenCurrentDataIsEmpty() {
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        RowChangeDTO deleted = new RowChangeDTO("DesignField", 200L);
        deleted.setAccessType(AccessType.DELETE);
        deleted.setCurrentData(new HashMap<>());
        Map<String, Object> before = new HashMap<>();
        before.put(ModelConstant.ID, 200L);
        before.put("modelId", 1L);
        before.put("modelName", "Customer");
        before.put("fieldName", "name");
        deleted.mergeDataBeforeChange(before);
        fieldChanges.addDeletedRow(deleted);

        PreviewTreeDTO tree = assembler.assemble(List.of(fieldChanges));

        assertEquals(1, tree.getModels().size());
        PreviewNodeDTO root = tree.getModels().getFirst();
        assertEquals("DesignModel", root.getModelName());
        assertEquals(1L, root.getRowId());
        assertEquals(ChangeKind.INDIRECT, root.getKind());
        assertEquals(1, root.getChildren().size());
        assertEquals(ChangeKind.DELETE, root.getChildren().getFirst().getKind());
    }

    @Test
    void emptyInputReturnsEmptyTree() {
        PreviewTreeDTO fromNull = assembler.assemble(null);
        assertTrue(fromNull.getModels().isEmpty());
        assertTrue(fromNull.getOptionSets().isEmpty());
        assertTrue(fromNull.getNavigations().isEmpty());

        PreviewTreeDTO fromEmpty = assembler.assemble(List.of());
        assertTrue(fromEmpty.getModels().isEmpty());
        assertTrue(fromEmpty.getOptionSets().isEmpty());
        assertTrue(fromEmpty.getNavigations().isEmpty());

        verify(modelService, never()).getByIds(any(), any(), any());
    }

    @Test
    void siblingsUnderSameRootArrayInDeclaredModelOrder() {
        ModelChangesDTO modelChanges = new ModelChangesDTO("DesignModel");
        modelChanges.addUpdatedRow(row("DesignModel", 1L, AccessType.UPDATE,
                Map.of(ModelConstant.ID, 1L, "modelName", "Customer")));
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addCreatedRow(row("DesignField", 200L, AccessType.CREATE,
                Map.of(ModelConstant.ID, 200L, "modelId", 1L, "modelName", "Customer", "fieldName", "name")));
        ModelChangesDTO indexChanges = new ModelChangesDTO("DesignModelIndex");
        indexChanges.addCreatedRow(row("DesignModelIndex", 800L, AccessType.CREATE,
                Map.of(ModelConstant.ID, 800L, "modelId", 1L, "modelName", "Customer", "name", "idx_name")));

        PreviewTreeDTO tree = assembler.assemble(List.of(modelChanges, fieldChanges, indexChanges));

        assertEquals(1, tree.getModels().size());
        List<PreviewNodeDTO> children = tree.getModels().getFirst().getChildren();
        assertEquals(2, children.size());
        // Per VERSION_CONTROL_MODELS: DesignField precedes DesignModelIndex.
        assertEquals("DesignField", children.get(0).getModelName());
        assertEquals("DesignModelIndex", children.get(1).getModelName());
    }

    @Test
    void mixedRootsLandInTheirOwnNamedBuckets() {
        ModelChangesDTO modelChanges = new ModelChangesDTO("DesignModel");
        modelChanges.addUpdatedRow(row("DesignModel", 1L, AccessType.UPDATE,
                Map.of(ModelConstant.ID, 1L, "modelName", "Customer")));
        ModelChangesDTO optionSetChanges = new ModelChangesDTO("DesignOptionSet");
        optionSetChanges.addCreatedRow(row("DesignOptionSet", 3L, AccessType.CREATE,
                Map.of(ModelConstant.ID, 3L, "name", "Status", "optionSetCode", "STATUS")));
        ModelChangesDTO navChanges = new ModelChangesDTO("DesignNavigation");
        navChanges.addUpdatedRow(row("DesignNavigation", 4L, AccessType.UPDATE,
                Map.of(ModelConstant.ID, 4L, "name", "CRM", "code", "crm")));

        PreviewTreeDTO tree = assembler.assemble(List.of(modelChanges, optionSetChanges, navChanges));

        assertEquals(1, tree.getModels().size());
        assertEquals("DesignModel", tree.getModels().getFirst().getModelName());
        assertEquals(1, tree.getOptionSets().size());
        assertEquals("DesignOptionSet", tree.getOptionSets().getFirst().getModelName());
        assertEquals(1, tree.getNavigations().size());
        assertEquals("DesignNavigation", tree.getNavigations().getFirst().getModelName());
    }

    private static RowChangeDTO row(String model, Long rowId, AccessType accessType, Map<String, Object> currentData) {
        RowChangeDTO r = new RowChangeDTO(model, rowId);
        r.setAccessType(accessType);
        r.setCurrentData(new HashMap<>(currentData));
        return r;
    }

}
