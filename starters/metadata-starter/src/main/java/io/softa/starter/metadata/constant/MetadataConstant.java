package io.softa.starter.metadata.constant;

import java.util.Map;
import com.google.common.collect.ImmutableMap;

public interface MetadataConstant {

    /** Servlet URL pattern (filter form) for the signed prefix. */
    String SIGNED_URL_PATTERN = "/upgrade/*";

    String METADATA_UPGRADE_API = "/upgrade/upgradeMetadata";
    String METADATA_EXPORT_API = "/upgrade/exportRuntimeMetadata";

    /** Version control model mapping relationship between design time and runtime */
    Map<String, String> VERSION_CONTROL_MODELS = ImmutableMap.<String, String>builder()
            .put("DesignModel", "SysModel")
            .put("DesignModelTrans", "SysModelTrans")
            .put("DesignField", "SysField")
            .put("DesignFieldTrans", "SysFieldTrans")
            .put("DesignModelIndex", "SysModelIndex")
            .put("DesignOptionSet", "SysOptionSet")
            .put("DesignOptionSetTrans", "SysOptionSetTrans")
            .put("DesignOptionItem", "SysOptionItem")
            .put("DesignOptionItemTrans", "SysOptionItemTrans")
            .put("DesignView",  "SysView")
            .put("DesignNavigation",  "SysNavigation")
            .build();
}
