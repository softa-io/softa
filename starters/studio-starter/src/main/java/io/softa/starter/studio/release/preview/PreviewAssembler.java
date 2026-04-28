package io.softa.starter.studio.release.preview;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.metadata.constant.MetadataConstant;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.enums.ChangeKind;

/**
 * Turns the flat {@code List<ModelChangesDTO>} produced by version-control aggregation
 * into a recursive tree rooted at {@code DesignModel}, {@code DesignOptionSet}, and
 * {@code DesignNavigation}, suitable for direct rendering by the preview UI.
 * <p>
 * Sub-records ({@code DesignField}, {@code DesignModelIndex}, {@code DesignOptionItem},
 * {@code DesignView}) and translation rows are nested under the root they belong to via
 * the FK columns recorded in {@link #PARENT_REFS}. Parents that are not themselves part
 * of the change set but are needed as containers are synthesized as
 * {@link ChangeKind#INDIRECT} nodes — either from the denormalized parent name carried
 * on the child row, or by a single batched DB lookup when no such denormalization exists
 * (translation rows and {@code DesignView}).
 */
@Component
public class PreviewAssembler {

    private static final Set<String> ROOTS = Set.of(
            "DesignModel", "DesignOptionSet", "DesignNavigation");

    /** child model → parent reference. */
    private static final Map<String, ParentRef> PARENT_REFS = Map.ofEntries(
            Map.entry("DesignField", new ParentRef("DesignModel", "modelId", "modelName")),
            Map.entry("DesignModelIndex", new ParentRef("DesignModel", "modelId", "modelName")),
            Map.entry("DesignModelTrans", new ParentRef("DesignModel", "rowId", null)),
            Map.entry("DesignFieldTrans", new ParentRef("DesignField", "rowId", null)),
            Map.entry("DesignOptionItem", new ParentRef("DesignOptionSet", "optionSetId", "optionSetCode")),
            Map.entry("DesignOptionSetTrans", new ParentRef("DesignOptionSet", "rowId", null)),
            Map.entry("DesignOptionItemTrans", new ParentRef("DesignOptionItem", "rowId", null)),
            Map.entry("DesignView", new ParentRef("DesignNavigation", "navId", null)));

    /**
     * Children of a parent are sorted by this model-name order so renders are stable and
     * mirror the canonical declaration sequence in {@link MetadataConstant#VERSION_CONTROL_MODELS}.
     */
    private static final Map<String, Integer> MODEL_ORDER = buildModelOrder();

    @Autowired
    private ModelService<Long> modelService;

    public PreviewTreeDTO assemble(List<ModelChangesDTO> flat) {
        PreviewTreeDTO tree = new PreviewTreeDTO();
        if (flat == null || flat.isEmpty()) {
            return tree;
        }

        Map<NodeKey, PreviewNodeDTO> index = buildIndex(flat);
        prefetchOrphanContainers(index);
        attachAllToParents(index);

        for (PreviewNodeDTO node : index.values()) {
            if (!ROOTS.contains(node.getModelName())) {
                continue;
            }
            sortChildrenRecursively(node);
            switch (node.getModelName()) {
                case "DesignModel" -> tree.getModels().add(node);
                case "DesignOptionSet" -> tree.getOptionSets().add(node);
                case "DesignNavigation" -> tree.getNavigations().add(node);
                default -> { /* unreachable: ROOTS gates the switch */ }
            }
        }
        Comparator<PreviewNodeDTO> byRowId = Comparator.comparingLong(
                n -> n.getRowId() == null ? Long.MAX_VALUE : n.getRowId());
        tree.getModels().sort(byRowId);
        tree.getOptionSets().sort(byRowId);
        tree.getNavigations().sort(byRowId);
        return tree;
    }

    private static Map<NodeKey, PreviewNodeDTO> buildIndex(List<ModelChangesDTO> flat) {
        Map<NodeKey, PreviewNodeDTO> index = new LinkedHashMap<>();
        for (ModelChangesDTO modelChanges : flat) {
            String modelName = modelChanges.getModelName();
            for (RowChangeDTO row : modelChanges.getCreatedRows()) {
                addLeaf(index, modelName, row, ChangeKind.CREATE);
            }
            for (RowChangeDTO row : modelChanges.getUpdatedRows()) {
                addLeaf(index, modelName, row, ChangeKind.UPDATE);
            }
            for (RowChangeDTO row : modelChanges.getDeletedRows()) {
                addLeaf(index, modelName, row, ChangeKind.DELETE);
            }
        }
        return index;
    }

    private static void addLeaf(Map<NodeKey, PreviewNodeDTO> index, String modelName,
                                RowChangeDTO row, ChangeKind kind) {
        NodeKey key = new NodeKey(modelName, row.getRowId());
        index.put(key, new PreviewNodeDTO(modelName, row.getRowId(), kind, row));
    }

    /**
     * Translation rows and {@code DesignView} do not carry their parent's display name on
     * the child record. When such a child's parent is not itself in the change set, batch
     * fetch the parent rows so we can render meaningful containers and continue walking up
     * (the fetched row carries the grandparent FK and denormalized name where applicable).
     */
    private void prefetchOrphanContainers(Map<NodeKey, PreviewNodeDTO> index) {
        Map<String, Set<Long>> orphanIdsByModel = new LinkedHashMap<>();
        for (PreviewNodeDTO node : index.values()) {
            ParentRef ref = PARENT_REFS.get(node.getModelName());
            if (ref == null || ref.denormParentNameColumn() != null) {
                continue;
            }
            Long parentId = extractParentId(node.getRecord(), ref);
            if (parentId == null) {
                continue;
            }
            NodeKey parentKey = new NodeKey(ref.parentModel(), parentId);
            if (!index.containsKey(parentKey)) {
                orphanIdsByModel.computeIfAbsent(ref.parentModel(), k -> new LinkedHashSet<>()).add(parentId);
            }
        }
        for (Map.Entry<String, Set<Long>> entry : orphanIdsByModel.entrySet()) {
            String parentModel = entry.getKey();
            List<Long> ids = new ArrayList<>(entry.getValue());
            List<Map<String, Object>> rows = modelService.getByIds(parentModel, ids, null);
            for (Map<String, Object> data : rows) {
                Long id = IdUtils.convertIdToLong(data.get(ModelConstant.ID));
                if (id == null) {
                    continue;
                }
                NodeKey key = new NodeKey(parentModel, id);
                if (index.containsKey(key)) {
                    continue;
                }
                RowChangeDTO synthetic = new RowChangeDTO(parentModel, id);
                synthetic.setCurrentData(new HashMap<>(data));
                index.put(key, new PreviewNodeDTO(parentModel, id, ChangeKind.INDIRECT, synthetic));
            }
        }
    }

    /**
     * For every non-root node, find its parent in the index and attach. When a parent is
     * missing, synthesize a {@link ChangeKind#INDIRECT} from the denormalized name on the
     * child row, add it to the index, and continue walking up — this naturally handles
     * multi-level orphans (e.g. {@code DesignFieldTrans} whose {@code DesignField} parent
     * was just fetched and whose grandparent {@code DesignModel} still needs synthesizing).
     */
    private static void attachAllToParents(Map<NodeKey, PreviewNodeDTO> index) {
        Set<NodeKey> attached = new HashSet<>();
        List<PreviewNodeDTO> worklist = new ArrayList<>(index.values());
        for (int i = 0; i < worklist.size(); i++) {
            PreviewNodeDTO node = worklist.get(i);
            if (ROOTS.contains(node.getModelName())) {
                continue;
            }
            NodeKey selfKey = new NodeKey(node.getModelName(), node.getRowId());
            if (attached.contains(selfKey)) {
                continue;
            }
            ParentRef ref = PARENT_REFS.get(node.getModelName());
            if (ref == null) {
                continue;
            }
            Long parentId = extractParentId(node.getRecord(), ref);
            if (parentId == null) {
                continue;
            }
            NodeKey parentKey = new NodeKey(ref.parentModel(), parentId);
            PreviewNodeDTO parent = index.get(parentKey);
            if (parent == null) {
                if (ref.denormParentNameColumn() == null) {
                    // Trans/view orphan whose pre-fetch returned no row (parent gone?). Skip.
                    continue;
                }
                parent = synthesizeContainer(node, ref, parentId);
                index.put(parentKey, parent);
                worklist.add(parent);
            }
            parent.getChildren().add(node);
            attached.add(selfKey);
        }
    }

    private static PreviewNodeDTO synthesizeContainer(PreviewNodeDTO child, ParentRef ref, Long parentId) {
        RowChangeDTO synthetic = new RowChangeDTO(ref.parentModel(), parentId);
        Map<String, Object> data = new HashMap<>();
        data.put(ModelConstant.ID, parentId);
        Map<String, Object> childData = child.getRecord() == null ? null : child.getRecord().getCurrentData();
        Object name = childData == null ? null : childData.get(ref.denormParentNameColumn());
        if (name != null) {
            data.put(ref.denormParentNameColumn(), name);
        }
        synthetic.setCurrentData(data);
        return new PreviewNodeDTO(ref.parentModel(), parentId, ChangeKind.INDIRECT, synthetic);
    }

    private static Long extractParentId(RowChangeDTO record, ParentRef ref) {
        if (record == null) {
            return null;
        }
        Long fromCurrent = IdUtils.convertIdToLong(record.getCurrentData().get(ref.fkColumn()));
        if (fromCurrent != null) {
            return fromCurrent;
        }
        return IdUtils.convertIdToLong(record.getDataBeforeChange().get(ref.fkColumn()));
    }

    private static void sortChildrenRecursively(PreviewNodeDTO node) {
        List<PreviewNodeDTO> children = node.getChildren();
        if (children.isEmpty()) {
            return;
        }
        children.sort(nodeOrderComparator());
        for (PreviewNodeDTO child : children) {
            sortChildrenRecursively(child);
        }
    }

    private static Comparator<PreviewNodeDTO> nodeOrderComparator() {
        return Comparator
                .comparingInt((PreviewNodeDTO n) -> MODEL_ORDER.getOrDefault(n.getModelName(), Integer.MAX_VALUE))
                .thenComparing(PreviewNodeDTO::getModelName)
                .thenComparingLong(n -> n.getRowId() == null ? Long.MAX_VALUE : n.getRowId());
    }

    private static Map<String, Integer> buildModelOrder() {
        Map<String, Integer> order = new HashMap<>();
        int i = 0;
        for (String key : MetadataConstant.VERSION_CONTROL_MODELS.keySet()) {
            order.put(key, i++);
        }
        return order;
    }

    private record NodeKey(String modelName, Long rowId) {
    }

    /**
     * Describes how a child design model references its parent.
     *
     * @param parentModel               parent design model name (e.g. {@code DesignModel})
     * @param fkColumn                  numeric FK column on the child row that points at the parent's id
     * @param denormParentNameColumn    optional denormalized parent name column on the child row
     *                                  (e.g. {@code modelName} on {@code DesignField}); {@code null}
     *                                  if the child does not carry one and the parent must be fetched
     *                                  from the DB to obtain its display name
     */
    private record ParentRef(String parentModel, String fkColumn, String denormParentNameColumn) {
    }

}
