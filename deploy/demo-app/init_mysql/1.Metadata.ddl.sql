CREATE TABLE sys_model(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    label_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label Name' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    display_name VARCHAR(255)   DEFAULT '' COMMENT 'Display Name' ,
    search_name VARCHAR(255)   DEFAULT '' COMMENT 'Search Name' ,
    default_order VARCHAR(256)    COMMENT 'Default Order' ,
    table_name VARCHAR(64)    COMMENT 'Table Name' ,
    soft_delete TINYINT(1)   DEFAULT 0 COMMENT 'Enable Soft Delete' ,
    soft_delete_field VARCHAR(64)    COMMENT 'Soft Delete Field Name' ,
    active_control TINYINT(1)    COMMENT 'Enable Active Control' ,
    timeline TINYINT(1)   DEFAULT 0 COMMENT 'Is Timeline Model' ,
    id_strategy VARCHAR(64)   DEFAULT 'DbAutoID' COMMENT 'ID Strategy' ,
    storage_type VARCHAR(64)   DEFAULT 'RDBMS' COMMENT 'Storage Type' ,
    version_lock TINYINT(1)   DEFAULT 0 COMMENT 'Enable Version Lock' ,
    multi_tenant TINYINT(1)   DEFAULT 0 COMMENT 'Enable Multi-tenancy' ,
    data_source VARCHAR(64)    COMMENT 'Data Source' ,
    service_name VARCHAR(64)    COMMENT 'Service Name' ,
    business_key VARCHAR(255)    COMMENT 'Business Primary Key' ,
    partition_field VARCHAR(64)   DEFAULT '' COMMENT 'Partition Field' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Model';


ALTER TABLE sys_model ADD UNIQUE INDEX uniq_modelname (model_name);

CREATE TABLE sys_language(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Name' ,
    code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    date_format VARCHAR(32)    COMMENT 'Date Format' ,
    time_format VARCHAR(32)    COMMENT 'Time Format' ,
    decimal_separator VARCHAR(32)    COMMENT 'Decimal Separator' ,
    thousand_separator VARCHAR(32)    COMMENT 'Thousand Separator' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Language';

CREATE TABLE sys_model_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    label_name VARCHAR(64)    COMMENT 'Label Name' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Model Translation';

CREATE TABLE sys_field(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    label_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label Name' ,
    field_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Name' ,
    column_name VARCHAR(64)   DEFAULT '' COMMENT 'Column Name' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    model_id BIGINT(32)    COMMENT 'Model ID' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    field_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Type' ,
    option_set_code VARCHAR(64)   DEFAULT '' COMMENT 'Option Set Code;Set when FieldType is Option or MultiOption' ,
    related_model VARCHAR(64)   DEFAULT '' COMMENT 'Related Model' ,
    related_field VARCHAR(64)   DEFAULT '' COMMENT 'Related Field' ,
    joint_model VARCHAR(64)    COMMENT 'Joint Model' ,
    joint_left VARCHAR(64)    COMMENT 'Joint Model Left Key' ,
    joint_right VARCHAR(64)    COMMENT 'Joint Model Right Key' ,
    cascaded_field VARCHAR(256)   DEFAULT '' COMMENT 'Cascaded Field' ,
    filters VARCHAR(256)   DEFAULT '' COMMENT 'Filters;Filters for relational fields.' ,
    default_value VARCHAR(256)   DEFAULT '' COMMENT 'Default Value' ,
    length INT(11)   DEFAULT 0 COMMENT 'Length' ,
    scale TINYINT(4)   DEFAULT 0 COMMENT 'Scale' ,
    required TINYINT(1)   DEFAULT 0 COMMENT 'Is Required' ,
    readonly TINYINT(1)   DEFAULT 0 COMMENT 'Is Readonly' ,
    hidden TINYINT(1)   DEFAULT 0 COMMENT 'Hidden' ,
    translatable TINYINT(1)   DEFAULT 0 COMMENT 'Translatable' ,
    non_copyable TINYINT(1)   DEFAULT 0 COMMENT 'Non Copyable' ,
    unsearchable TINYINT(1)   DEFAULT 0 COMMENT 'Unsearchable' ,
    computed TINYINT(1)   DEFAULT 0 COMMENT 'Is Computed' ,
    expression TEXT(20000)    COMMENT 'Expression' ,
    dynamic TINYINT(1)   DEFAULT 0 COMMENT 'Dynamic Field' ,
    encrypted TINYINT(1)   DEFAULT 0 COMMENT 'Is Encrypted' ,
    masking_type VARCHAR(64)    COMMENT 'Masking Type' ,
    widget_type VARCHAR(64)    COMMENT 'Widget Type' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Field';


ALTER TABLE sys_field ADD UNIQUE INDEX uniq_modelname_fieldname (model_name,field_name);

CREATE TABLE sys_field_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    label_name VARCHAR(64)    COMMENT 'Label Name' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Field Translation';

CREATE TABLE sys_option_set(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Option Set Name' ,
    option_set_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Option Set Code' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Option Set';

CREATE TABLE sys_option_set_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    name VARCHAR(64)    COMMENT 'Option Set Name' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Option Set Translation';

CREATE TABLE sys_option_item(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    option_set_id BIGINT(32)    COMMENT 'Option Set ID' ,
    option_set_code VARCHAR(64)   DEFAULT '' COMMENT 'Option Set Code' ,
    sequence INT(11)   DEFAULT 1 COMMENT 'Sequence' ,
    item_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Item Code' ,
    item_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Item Name' ,
    parent_item_code VARCHAR(64)   DEFAULT '' COMMENT 'Parent Item Code' ,
    item_color VARCHAR(64)   DEFAULT '' COMMENT 'Item Color' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Option Items';

CREATE TABLE sys_option_item_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    item_name VARCHAR(64)    COMMENT 'Item Name' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Option Items Translation';

CREATE TABLE sys_config(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)   DEFAULT 0 COMMENT 'App ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Name' ,
    code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Code' ,
    value TEXT NOT NULL   COMMENT 'Value' ,
    value_type VARCHAR(64)   DEFAULT '' COMMENT 'Value Data Type' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Config';


ALTER TABLE sys_config ADD UNIQUE INDEX uniq_config_key (code);

CREATE TABLE sys_view(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    model_name VARCHAR(64)   DEFAULT '' COMMENT 'Model Name' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'View Name' ,
    code VARCHAR(64)   DEFAULT '' COMMENT 'View Code' ,
    type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'View Type' ,
    sequence TINYINT(4)   DEFAULT 1 COMMENT 'Sequence' ,
    structure TEXT NOT NULL   COMMENT 'Structure' ,
    default_filter TEXT    COMMENT 'Default Filters;View level default filter.' ,
    default_order TEXT    COMMENT 'Default Order;The default sorting condition at the view level.' ,
    nav_id BIGINT(32)   DEFAULT 0 COMMENT 'Navigation ID' ,
    public_view TINYINT(1)   DEFAULT 1 COMMENT 'Public View' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System View';

CREATE TABLE sys_view_default(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    view_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'View ID' ,
    view_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'View Code' ,
    nav_id BIGINT(32)   DEFAULT 0 COMMENT 'Navigation ID' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'User Default View';

CREATE TABLE import_template(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    tenant_id VARCHAR(32)    COMMENT 'Tenant ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Template Name' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    import_rule VARCHAR(64)    COMMENT 'Import Rule' ,
    unique_constraints VARCHAR(255)    COMMENT 'Unique Constraints' ,
    ignore_empty TINYINT(1)   DEFAULT true COMMENT 'Ignore Empty Value' ,
    skip_exception TINYINT(1)   DEFAULT true COMMENT 'Skip Abnormal Data' ,
    custom_handler VARCHAR(128)    COMMENT 'Custom Import Handler;The simpleName of the CustomImportHandler interface implementation class' ,
    sync_import TINYINT(1)    COMMENT 'Synchronous Import;Default is asynchronous import via MQ.' ,
    include_description TINYINT(1)    COMMENT 'Include Import Description' ,
    description VARCHAR(1000)    COMMENT 'Description' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Import Template';

CREATE TABLE file_record(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    tenant_id VARCHAR(32)    COMMENT 'Tenant ID' ,
    file_name VARCHAR(128) NOT NULL  DEFAULT '' COMMENT 'File Name' ,
    oss_key VARCHAR(128)    COMMENT 'OSS Key' ,
    file_type VARCHAR(64)    COMMENT 'File Type' ,
    file_size INT(11)    COMMENT 'File Size(KB)' ,
    checksum VARCHAR(64)    COMMENT 'Checksum;SHA-256 Hash Algorithm' ,
    model_name VARCHAR(64)    COMMENT 'Model Name' ,
    field_name VARCHAR(64)    COMMENT 'Field name' ,
    row_id VARCHAR(64)    COMMENT 'Row ID' ,
    source VARCHAR(64)    COMMENT 'Source' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'File Record';


ALTER TABLE file_record ADD UNIQUE INDEX uniq_oss_key (oss_key);

CREATE TABLE import_history(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    tenant_id VARCHAR(32)    COMMENT 'Tenant ID' ,
    template_id BIGINT(32)    COMMENT 'Template ID' ,
    original_file_id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'Original File ID' ,
    file_name VARCHAR(256) NOT NULL  DEFAULT '' COMMENT 'File Name' ,
    status VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Import Status' ,
    failed_file_id VARCHAR(32)    COMMENT 'Failed File ID' ,
    total_rows INT(11)   DEFAULT 0 COMMENT 'Total Rows' ,
    success_rows INT(11)   DEFAULT 0 COMMENT 'Success Rows' ,
    failed_rows INT(11)   DEFAULT 0 COMMENT 'Failed Rows' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    deleted TINYINT(1)    COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Import History';

CREATE TABLE export_template(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    tenant_id VARCHAR(32)    COMMENT 'Tenant ID' ,
    file_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'File Name;FileName default.' ,
    sheet_name VARCHAR(64)    COMMENT 'Sheet Name;If it is empty, it is the same as the file name' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    file_id VARCHAR(32)    COMMENT 'File Template ID' ,
    filters VARCHAR(1000)    COMMENT 'Filters' ,
    orders VARCHAR(256)    COMMENT 'Orders;The orders of the exported data.' ,
    custom_handler VARCHAR(128)    COMMENT 'Custom Export Handler;The simpleName of the CustomExportHandler interface implementation class' ,
    enable_transpose TINYINT(1)    COMMENT 'Enable Transpose;Transpose the data filled in the file template.' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Export Template';

CREATE TABLE export_history(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    tenant_id VARCHAR(32)    COMMENT 'Tenant ID' ,
    template_id BIGINT(32)    COMMENT 'Template ID' ,
    exported_file_id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'Exported File ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Export History';

CREATE TABLE import_template_field(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    template_id BIGINT(32)    COMMENT 'Import Template ID' ,
    field_name VARCHAR(128) NOT NULL  DEFAULT '' COMMENT 'Field Name;Cascading fields are supported' ,
    custom_header VARCHAR(64)    COMMENT 'Custom Header;Custom header for imported field' ,
    sequence INT(11)   DEFAULT 1 COMMENT 'Sequence' ,
    required TINYINT(1)    COMMENT 'Required' ,
    default_value VARCHAR(128)    COMMENT 'Default Value;Support string interpolation calculation `#{}`.' ,
    description VARCHAR(256)    COMMENT 'Description;Field introduction or import-related help and tips.' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Import Template Fields';

CREATE TABLE export_template_field(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    template_id BIGINT(32)    COMMENT 'Export Template ID' ,
    field_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Name;Cascading fields are supported' ,
    custom_header VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Custom Header;Custom header for exported field' ,
    sequence INT(11)   DEFAULT 1 COMMENT 'Sequence' ,
    ignored TINYINT(1)    COMMENT 'Ignored In File;Only used in custom handler' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Export Template Field';

CREATE TABLE flow_node(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Node Name' ,
    flow_id BIGINT(32) NOT NULL   COMMENT 'Flow ID' ,
    stage_id BIGINT(32)   DEFAULT 0 COMMENT 'Stage ID' ,
    node_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Node Type' ,
    sequence TINYINT(4)   DEFAULT 1 COMMENT 'Sequence' ,
    parent_id BIGINT(32)    COMMENT 'Parent Node ID' ,
    node_condition VARCHAR(1000)   DEFAULT '' COMMENT 'Node Execute Condition' ,
    node_params TEXT    COMMENT 'Node Params' ,
    exception_policy TEXT    COMMENT 'Exception Policy;Mainly in scenarios of GetData and ComputeData Nodes' ,
    position TEXT    COMMENT 'Position' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Flow Node';

CREATE TABLE flow_trigger(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    name VARCHAR(64)   DEFAULT '' COMMENT 'Trigger Name' ,
    flow_id BIGINT(32)    COMMENT 'Triggered Flow' ,
    event_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Trigger Event Type' ,
    source_model VARCHAR(64)   DEFAULT '' COMMENT 'Source Model' ,
    source_fields VARCHAR(255)   DEFAULT '' COMMENT 'Source Fields;Triggered when any field in the list is modified' ,
    trigger_condition VARCHAR(1000)   DEFAULT '' COMMENT 'Trigger Condition' ,
    cron_id BIGINT(32)    COMMENT 'Cron Job ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Flow Trigger';

CREATE TABLE flow_config(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Flow Name' ,
    flow_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Flow Type' ,
    layout_type VARCHAR(64)    COMMENT 'Layout Type' ,
    sync TINYINT(1)   DEFAULT 0 COMMENT 'Is Sync Executed Flow;When True, it is executed within the same transaction as the triggering event. By default, it is executed asynchronously.' ,
    debug_mode TINYINT(1)   DEFAULT 0 COMMENT 'Enable Debug Model;Record the complete execution info of the Flow in debug mode' ,
    rollback_on_fail TINYINT(1)   DEFAULT 1 COMMENT 'Rollback On Fail' ,
    readonly TINYINT(1)   DEFAULT 0 COMMENT 'Readonly Flow;When True, updating the database is not allowed' ,
    version VARCHAR(64)   DEFAULT '' COMMENT 'Version' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Flow Description' ,
    data_scope VARCHAR(1000)   DEFAULT '' COMMENT 'Data Scope' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Flow Config';

CREATE TABLE flow_instance(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    model_name VARCHAR(64)    COMMENT 'Main Model' ,
    row_id VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Row Data ID' ,
    flow_id BIGINT(32) NOT NULL   COMMENT 'Flow ID' ,
    flow_type VARCHAR(64)    COMMENT 'Flow Type' ,
    trigger_id BIGINT(32)    COMMENT 'Trigger ID' ,
    current_node_id BIGINT(32)    COMMENT 'Current Node ID' ,
    current_status VARCHAR(64)    COMMENT 'Current Status' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Flow Instance';

CREATE TABLE flow_debug_history(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    flow_id BIGINT(32) NOT NULL   COMMENT 'Flow ID' ,
    flow_type VARCHAR(64)    COMMENT 'Flow Type' ,
    flow_status VARCHAR(64)    COMMENT 'Flow Status' ,
    main_flow_id BIGINT(32)    COMMENT 'Main Flow ID;Top-level Flow ID' ,
    parent_flow_id BIGINT(32)    COMMENT 'Parent Flow ID;When multiple sub-flow are called, the parent Flow ID is not equal to the main Flow ID' ,
    start_time DATETIME    COMMENT 'Start Time;The start time of the current flow instance' ,
    end_time DATETIME    COMMENT 'End Time;The end time of the current flow instance' ,
    event_message TEXT NOT NULL   COMMENT 'Event Message;Event message that triggers the flow' ,
    node_trace TEXT    COMMENT 'Node Trace;Details tracking for each node, including input parameters, output parameters, etc.' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Flow Debug History';

CREATE TABLE flow_edge(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    flow_id BIGINT(32) NOT NULL   COMMENT 'Flow ID' ,
    label VARCHAR(64)    COMMENT 'Edge Label' ,
    source_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Source Node ID' ,
    target_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Target Node ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Flow Edge';


ALTER TABLE flow_edge ADD UNIQUE INDEX unique_source_target (source_id,target_id);

CREATE TABLE sys_model_onchange(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Name' ,
    code VARCHAR(64)   DEFAULT '' COMMENT 'Code' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    onchange_fields VARCHAR(255) NOT NULL  DEFAULT '' COMMENT 'Onchange Fields' ,
    expression VARCHAR(1000) NOT NULL  DEFAULT '' COMMENT 'Expression' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Model Onchange Event;页面元素的字段变更事件';

CREATE TABLE sys_model_validation(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Name' ,
    code VARCHAR(64)   DEFAULT '' COMMENT 'Code' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    priority TINYINT(4) NOT NULL  DEFAULT 1 COMMENT 'priority' ,
    expression VARCHAR(1000) NOT NULL  DEFAULT '' COMMENT 'Expression' ,
    exception_msg VARCHAR(256) NOT NULL  DEFAULT '' COMMENT 'Exception Message' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Model Validation;模型数据创建、更新、删除时的同步校验规则';

CREATE TABLE flow_stage(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    flow_id BIGINT(32) NOT NULL   COMMENT 'Flow ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Stage Name' ,
    description VARCHAR(64)    COMMENT 'Stage Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Flow Stage';

CREATE TABLE flow_event(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    flow_id BIGINT(32)    COMMENT 'Flow ID' ,
    node_id BIGINT(32)    COMMENT 'Node ID' ,
    trigger_id BIGINT(32)    COMMENT 'Trigger ID' ,
    trigger_type VARCHAR(64)    COMMENT 'Trigger Type' ,
    source_model VARCHAR(64)    COMMENT 'Source Model' ,
    row_id VARCHAR(64)    COMMENT 'Row Data ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Flow Event';

CREATE TABLE design_app(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'App Name' ,
    app_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'App Code' ,
    app_type VARCHAR(64)   DEFAULT '' COMMENT 'App Type' ,
    database_type VARCHAR(64)   DEFAULT '' COMMENT 'Database Type' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    package_name VARCHAR(64)   DEFAULT '' COMMENT 'Package Name;Fill in when you need to generate code, the model in the same App belongs to the same Module.' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design App';

CREATE TABLE design_app_env(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Env Name' ,
    app_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'App ID' ,
    app_code VARCHAR(64)   DEFAULT '' COMMENT 'App Code' ,
    env_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Env Type' ,
    upgrade_endpoint VARCHAR(128) NOT NULL  DEFAULT '' COMMENT 'Upgrade API EndPoint' ,
    last_publish_time DATETIME    COMMENT 'Last Publish Time' ,
    client_id VARCHAR(64)   DEFAULT '' COMMENT 'Client ID;OAuth2 Client ID' ,
    client_secret VARCHAR(256)   DEFAULT '' COMMENT 'Client Secret;OAuth2 Client Secret' ,
    async_upgrade TINYINT(1)   DEFAULT 0 COMMENT 'Async Upgrade;Synchronous upgrade invokes the upgrade API directly, asynchronous upgrade uses MQ first and then the upgrade API, and the default synchronization' ,
    auto_upgrade TINYINT(1)   DEFAULT 0 COMMENT 'Auto Upgrade;Metadata is automatically synchronized' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design App Env';

CREATE TABLE design_app_version(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)   DEFAULT 0 COMMENT 'App ID' ,
    env_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Env ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Version Name' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Upgrade description' ,
    ddl_table MEDIUMTEXT    COMMENT 'Table DDL' ,
    ddl_index TEXT(20000)    COMMENT 'Index DDL' ,
    versioned_content MEDIUMTEXT    COMMENT 'Version Content' ,
    last_versioned_time DATETIME    COMMENT 'Last Versioned Time' ,
    published TINYINT(1)   DEFAULT 0 COMMENT 'Published;Once published, it is released' ,
    last_publish_time DATETIME    COMMENT 'Last Publish Time' ,
    locked TINYINT(1)   DEFAULT 0 COMMENT 'Locked;When a new version is created, the historical version is automatically locked and cannot be modified.' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design App Version';

CREATE TABLE design_app_env_merge(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'App ID' ,
    source_env_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Source Env ID' ,
    target_env_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Target Env ID' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Merge description' ,
    merge_content MEDIUMTEXT    COMMENT 'Merge Content' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design App Env Merge';

CREATE TABLE design_app_version_published(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'App ID' ,
    env_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Env ID' ,
    version_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Version ID' ,
    publish_status VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Publish Status' ,
    publish_duration DOUBLE(24,2)   DEFAULT 0 COMMENT 'Publish Duration (S)' ,
    publish_content MEDIUMTEXT    COMMENT 'Publish Content' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design App Version Published';

CREATE TABLE sys_app(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'App Name' ,
    app_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'App Code' ,
    app_type VARCHAR(64)   DEFAULT '' COMMENT 'App Type' ,
    database_type VARCHAR(64)   DEFAULT '' COMMENT 'Database Type' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Application';

CREATE TABLE sys_data_source(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    name VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'Data Source Name' ,
    ds_key VARCHAR(32)    COMMENT 'Data Source Key' ,
    jdbc_url VARCHAR(256) NOT NULL  DEFAULT '' COMMENT 'JDBC URL' ,
    username VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Username' ,
    password VARCHAR(256) NOT NULL  DEFAULT '' COMMENT 'Password' ,
    initial_size INT(11) NOT NULL   COMMENT 'Initial Pool Size' ,
    max_active INT(11) NOT NULL   COMMENT 'Maximum Pool Size' ,
    min_idle INT(11) NOT NULL   COMMENT 'Minimum Idle' ,
    max_wait INT(11) NOT NULL   COMMENT 'Connection Timeout' ,
    readonly TINYINT(1)    COMMENT 'Readonly' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'External Data Source;The default datasource is configured in the spring.datasource configuration.
This model is used to configure additional external data sources.';

CREATE TABLE design_field_type_mapping(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    database_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Database Type' ,
    field_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Type' ,
    column_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Column Type' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Field Type Mapping;Mapping between the FieldType and the column type of the database.';

CREATE TABLE sys_model_index(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)   DEFAULT 0 COMMENT 'APP ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Index Title' ,
    code VARCHAR(64)   DEFAULT '' COMMENT 'Index Code' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    model_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Model ID' ,
    index_name VARCHAR(64)   DEFAULT '' COMMENT 'Index Name' ,
    index_fields VARCHAR(255)   DEFAULT '' COMMENT 'Index Fields' ,
    unique_index TINYINT(1)   DEFAULT 0 COMMENT 'Is Unique Index' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Model Index';

CREATE TABLE auth_registered_client(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    client_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '客户端名称' ,
    client_id VARCHAR(64)   DEFAULT '' COMMENT 'Client ID;OAuth2 客户端 ID' ,
    client_secret VARCHAR(256)   DEFAULT '' COMMENT 'Client Secret;OAuth2 客户端 Secret，编码后存储' ,
    expired_date DATE    COMMENT '失效日期' ,
    grant_type VARCHAR(64)   DEFAULT '' COMMENT '授权类型;同步升级走API，异步升级先走MQ再走API，默认同步' ,
    description VARCHAR(256)   DEFAULT '' COMMENT '客户端描述' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '已注册客户端';


ALTER TABLE auth_registered_client ADD UNIQUE INDEX uniq_clientid (client_id);

CREATE TABLE sys_filter(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Filter Name' ,
    filters VARCHAR(1000) NOT NULL  DEFAULT '' COMMENT 'Filter Conditions' ,
    model VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    query VARCHAR(256)   DEFAULT '' COMMENT 'Query Text;Natural language queries are converted to filters.' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Filter';

CREATE TABLE sys_pre_data(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    model VARCHAR(64)    COMMENT 'Model Name' ,
    pre_id VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Predefined ID' ,
    row_id BIGINT(32)    COMMENT 'Row Data ID' ,
    frozen TINYINT(1)    COMMENT 'Frozen' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Predefined Data';


ALTER TABLE sys_pre_data ADD UNIQUE INDEX unique_model_preid (model,pre_id);

CREATE TABLE sys_navigation(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Name' ,
    type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Type' ,
    code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Code' ,
    model_name VARCHAR(256)   DEFAULT '' COMMENT 'Model Name' ,
    parent_id BIGINT(32)    COMMENT 'Parent Navigation' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    filter VARCHAR(256)   DEFAULT '' COMMENT 'Default filters;The default filters at the menu level.' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Navigation';

CREATE TABLE sys_cron(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    name VARCHAR(64)   DEFAULT '' COMMENT 'Cron Job Name' ,
    cron_expression VARCHAR(64)   DEFAULT '' COMMENT 'Cron Expression;Use Quartz seven-field format with seconds and years.' ,
    cron_semantic VARCHAR(64)   DEFAULT '' COMMENT 'Semantic Description' ,
    limit_execution TINYINT(1)    COMMENT 'Limit the Execution Times' ,
    remaining_count TINYINT(4)   DEFAULT -1 COMMENT 'Remaining Execution Times;Subtract 1 after each execution. If the value is less than 1, no more execution is performed. Clear the next execution time and set Enable to false.' ,
    next_exec_time DATETIME    COMMENT 'Next Execution Time;每次执行成功后，计算并更新。允许回拨补偿运行。' ,
    last_exec_time DATETIME    COMMENT 'Last Execution Time;每次执行成功后，记录开始执行时间，允许回拨补偿运行变更数据。' ,
    redo_misfire TINYINT(1)   DEFAULT 0 COMMENT 'Redo Missed Task;默认不补偿，为true时仅立即补偿一次' ,
    priority TINYINT(4)   DEFAULT 1 COMMENT 'Priority;数字越小，优先级越高，0-10' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Cron;执行任务时，携带任务归属时间，支持错过的任务自动补偿机制。';

CREATE TABLE sys_cron_log(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    cron_id BIGINT(32)    COMMENT 'Cron ID' ,
    cron_name VARCHAR(64)   DEFAULT '' COMMENT 'Cron Job Name' ,
    status VARCHAR(64)   DEFAULT 'Running' COMMENT 'Cron Execution State' ,
    start_time DATETIME    COMMENT 'Execution Start Time;Update when execution begins.' ,
    end_time DATETIME    COMMENT 'Execution End Time;Update after execution' ,
    total_time DOUBLE(24,2)    COMMENT 'Total Execution Time (s)' ,
    error_message VARCHAR(256)    COMMENT 'Error Message' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Cron Log';

CREATE TABLE change_log(
    uuid VARCHAR(64)    COMMENT 'UUID' ,
    trace_id VARCHAR(64)    COMMENT 'Trace ID' ,
    model VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    row_id BIGINT(32) NOT NULL   COMMENT 'Row ID' ,
    access_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Access Type' ,
    data_before_change TEXT    COMMENT 'Data Before Change' ,
    data_after_change TEXT    COMMENT 'Data After Change' ,
    changed_id BIGINT(32)    COMMENT 'Changed ID' ,
    changed_by VARCHAR(64)    COMMENT 'Changed By' ,
    changed_time DATETIME    COMMENT 'Changed Time'
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Change Log';

CREATE TABLE mail_record(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    subject VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '主题' ,
    content TEXT(20000)    COMMENT '正文' ,
    sender VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '发送人' ,
    receivers VARCHAR(1000) NOT NULL  DEFAULT '' COMMENT '接收人;多个逗号分隔' ,
    carbon_copiers VARCHAR(1000) NOT NULL  DEFAULT '' COMMENT '抄送人;多个逗号分隔' ,
    blind_carbon_copiers VARCHAR(1000) NOT NULL  DEFAULT '' COMMENT '密送人;多个逗号分隔' ,
    attachments VARCHAR(1024) NOT NULL  DEFAULT '' COMMENT '附件;多个逗号分隔' ,
    send_result VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '发送结果;字典：结果（Result）' ,
    fail_reason VARCHAR(1000) NOT NULL  DEFAULT '' COMMENT '失败原因' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1) NOT NULL  DEFAULT 0 COMMENT 'Deleted' ,
    created_time DATETIME NOT NULL   COMMENT 'Created Time' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME NOT NULL   COMMENT 'Updated Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '邮件记录';

CREATE TABLE mail_template(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    code VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '模版编码' ,
    name VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '模版名称' ,
    content TEXT(20000)    COMMENT '模版内容' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1) NOT NULL  DEFAULT 0 COMMENT 'Deleted' ,
    created_time DATETIME NOT NULL   COMMENT 'Created Time' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME NOT NULL   COMMENT 'Updated Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '邮件模版';

CREATE TABLE mail_sender_server(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    tenant_id BIGINT(32)    COMMENT '租户ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '服务器名称' ,
    smpt_host VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'SMTP服务地址' ,
    smpt_port VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'SMTP端口' ,
    username VARCHAR(64)    COMMENT 'Username' ,
    password VARCHAR(64)    COMMENT '密码' ,
    enable_tls TINYINT(1)    COMMENT '启用TLS' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '邮件发送服务器';

CREATE TABLE dept_info2(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    tenant_id BIGINT(32)    COMMENT '租户ID' ,
    code VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '部门编码' ,
    name VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '部门名称' ,
    alias VARCHAR(256)    COMMENT '部门别名' ,
    leader_id BIGINT(32)    COMMENT '部门负责人ID' ,
    parent_id BIGINT(32)    COMMENT '父级部门ID' ,
    dept_id_full_path VARCHAR(1000)    COMMENT '部门ID全路径' ,
    dept_rank VARCHAR(64)    COMMENT '部门级别' ,
    type VARCHAR(64)    COMMENT '部门类型' ,
    label VARCHAR(64)    COMMENT '部门标签' ,
    state VARCHAR(64)    COMMENT '部门状态' ,
    dept_level VARCHAR(64)    COMMENT '部门层级' ,
    sequence TINYINT(4)   DEFAULT 0 COMMENT '序号' ,
    dept_code_full_path VARCHAR(1000)    COMMENT '部门code全路径' ,
    dept_name_full_path VARCHAR(1000)    COMMENT '部门名称全路径' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '部门信息';

CREATE TABLE access_scope_dimension(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    code VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '编码' ,
    name VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '名称' ,
    field_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '字段类型' ,
    widget VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '组件' ,
    option_set_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '选项集编码' ,
    operator VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '操作' ,
    sequence INT(11) NOT NULL  DEFAULT 0 COMMENT '排序' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1) NOT NULL  DEFAULT 0 COMMENT 'Deleted' ,
    created_id BIGINT(32) NOT NULL   COMMENT 'Created ID' ,
    created_time DATETIME NOT NULL   COMMENT 'Created Time' ,
    updated_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME NOT NULL   COMMENT 'Updated Time' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '权限范围维度表';

CREATE TABLE access_model_attribute(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '权限维度' ,
    model VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '模型编码' ,
    field VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '字段编码' ,
    alias VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '字段别名' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1) NOT NULL  DEFAULT 0 COMMENT 'Deleted' ,
    created_id BIGINT(32) NOT NULL   COMMENT 'Created ID' ,
    created_time DATETIME NOT NULL   COMMENT 'Created Time' ,
    updated_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME NOT NULL   COMMENT 'Updated Time' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '模型权限属性表';

CREATE TABLE access_role_field(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    role_id BIGINT(32) NOT NULL   COMMENT '关联角色' ,
    model VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '模型名称' ,
    field VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '字段名称' ,
    readable TINYINT(1) NOT NULL  DEFAULT 0 COMMENT '查看;可查看，默认：否' ,
    updatable TINYINT(1) NOT NULL  DEFAULT 0 COMMENT '更新;可编辑，默认：否' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1) NOT NULL  DEFAULT 0 COMMENT 'Deleted' ,
    created_id BIGINT(32) NOT NULL   COMMENT 'Created ID' ,
    created_time DATETIME NOT NULL   COMMENT 'Created Time' ,
    updated_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME NOT NULL   COMMENT 'Updated Time' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '角色字段关系表';

CREATE TABLE access_role_navigation(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    role_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT '角色ID' ,
    navigation_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT '导航ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1) NOT NULL  DEFAULT 0 COMMENT 'Deleted' ,
    created_id BIGINT(32) NOT NULL   COMMENT 'Created ID' ,
    created_time DATETIME NOT NULL   COMMENT 'Created Time' ,
    updated_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME NOT NULL   COMMENT 'Updated Time' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '角色导航关系表';

CREATE TABLE access_role(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    tenant_id BIGINT(32)    COMMENT '租户ID' ,
    code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '角色编码' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '角色名称' ,
    description VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '描述信息' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    preview TEXT(20000)    COMMENT '预览信息' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '角色';

CREATE TABLE access_action(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '操作编码' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '操作名称' ,
    api_uri VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '接口地址' ,
    navigation_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT '导航ID' ,
    access_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '访问类型' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1) NOT NULL  DEFAULT 0 COMMENT 'Deleted' ,
    created_id BIGINT(32) NOT NULL   COMMENT 'Created ID' ,
    created_time DATETIME NOT NULL   COMMENT 'Created Time' ,
    updated_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME NOT NULL   COMMENT 'Updated Time' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '操作';

CREATE TABLE access_role_action(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    role_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT '角色ID' ,
    action_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT '操作ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1) NOT NULL  DEFAULT 0 COMMENT 'Deleted' ,
    created_id BIGINT(32) NOT NULL   COMMENT 'Created ID' ,
    created_time DATETIME NOT NULL   COMMENT 'Created Time' ,
    updated_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME NOT NULL   COMMENT 'Updated Time' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '角色操作关联';

CREATE TABLE access_role_rule(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    role_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT '角色ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1) NOT NULL  DEFAULT 0 COMMENT 'Deleted' ,
    created_id BIGINT(32) NOT NULL   COMMENT 'Created ID' ,
    created_time DATETIME NOT NULL   COMMENT 'Created Time' ,
    updated_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME NOT NULL   COMMENT 'Updated Time' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '角色规则关系';

CREATE TABLE access_role_rule_user(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    role_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT '角色ID' ,
    role_rule_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT '规则ID' ,
    filters VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '过滤器' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1) NOT NULL  DEFAULT 0 COMMENT 'Deleted' ,
    created_id BIGINT(32) NOT NULL   COMMENT 'Created ID' ,
    created_time DATETIME NOT NULL   COMMENT 'Created Time' ,
    updated_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME NOT NULL   COMMENT 'Updated Time' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '角色规则用户关系';

CREATE TABLE access_role_rule_scope(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    role_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT '角色ID' ,
    role_rule_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT '规则ID' ,
    type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '维度' ,
    model VARCHAR(256)    COMMENT '模型名称' ,
    filters VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '过滤条件' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1) NOT NULL  DEFAULT 0 COMMENT 'Deleted' ,
    created_id BIGINT(32) NOT NULL   COMMENT 'Created ID' ,
    created_time DATETIME NOT NULL   COMMENT 'Created Time' ,
    updated_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME NOT NULL   COMMENT 'Updated Time' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '角色规则范围关系';

CREATE TABLE access_user_dimension(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    name VARCHAR(256) NOT NULL  DEFAULT '' COMMENT '名称' ,
    field VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '字段' ,
    field_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '字段类型' ,
    widget VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '组件' ,
    option_set_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '选项集编码' ,
    operator VARCHAR(64) NOT NULL  DEFAULT '' COMMENT '操作' ,
    sequence INT(11) NOT NULL   COMMENT '排序' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1) NOT NULL  DEFAULT 0 COMMENT 'Deleted' ,
    created_id BIGINT(32) NOT NULL   COMMENT 'Created ID' ,
    created_time DATETIME NOT NULL   COMMENT 'Created Time' ,
    updated_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Updated ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME NOT NULL   COMMENT 'Updated Time' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '权限用户维度表';

CREATE TABLE dept_info(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Name' ,
    code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Code' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Dept Info;字段类型FieldType与数据库Column Type的对应关系';

CREATE TABLE project_info(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Name' ,
    code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Code' ,
    cost DOUBLE(24,2)    COMMENT 'Cost' ,
    profit DECIMAL(32,8)    COMMENT 'Profit' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Project Info;字段类型FieldType与数据库Column Type的对应关系';

CREATE TABLE emp_info(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Name' ,
    code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Code' ,
    email VARCHAR(64)   DEFAULT '' COMMENT 'Email' ,
    dept_id BIGINT(32)    COMMENT 'Department' ,
    photo VARCHAR(32)    COMMENT 'Employee Photo' ,
    documents VARCHAR(1024)    COMMENT 'Employee Documents' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    tenant_id BIGINT(32)   DEFAULT 0 COMMENT 'TenantID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Employee Info';

CREATE TABLE emp_project_rel(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    emp_id BIGINT(32)    COMMENT 'Employee ID' ,
    project_id BIGINT(32)    COMMENT 'Project ID' ,
    tenant_id BIGINT(32)   DEFAULT 0 COMMENT 'Tenant ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Employee Project Relation;字段类型FieldType与数据库Column Type的对应关系';

CREATE TABLE document_template(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    tenant_id VARCHAR(32)    COMMENT 'Tenant ID' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    file_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'File Name;FileName default.' ,
    file_id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'File Template ID' ,
    convert_to_pdf TINYINT(1)    COMMENT 'Convert To PDF' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Document Template';

CREATE TABLE design_model(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    external_id BIGINT(32)    COMMENT 'External ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    env_id BIGINT(32)    COMMENT 'Env ID' ,
    label_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label Name' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    display_name VARCHAR(255)   DEFAULT '' COMMENT 'Display Name' ,
    search_name VARCHAR(255)   DEFAULT '' COMMENT 'Search Name' ,
    default_order VARCHAR(256)    COMMENT 'Default Order' ,
    table_name VARCHAR(64)    COMMENT 'Table Name' ,
    soft_delete TINYINT(1)   DEFAULT 0 COMMENT 'Enable Soft Delete' ,
    soft_delete_field VARCHAR(64)    COMMENT 'Soft Delete Field Name' ,
    active_control TINYINT(1)    COMMENT 'Enable Active Control' ,
    timeline TINYINT(1)   DEFAULT 0 COMMENT 'Is Timeline Model' ,
    id_strategy VARCHAR(64)   DEFAULT 'DbAutoID' COMMENT 'ID Strategy' ,
    storage_type VARCHAR(64)   DEFAULT 'RDBMS' COMMENT 'Storage Type' ,
    version_lock TINYINT(1)   DEFAULT 0 COMMENT 'Enable Version Lock' ,
    multi_tenant TINYINT(1)   DEFAULT 0 COMMENT 'Enable Multi-tenancy' ,
    data_source VARCHAR(64)    COMMENT 'Data Source' ,
    service_name VARCHAR(64)    COMMENT 'Service Name' ,
    business_key VARCHAR(255)    COMMENT 'Business Primary Key' ,
    partition_field VARCHAR(64)   DEFAULT '' COMMENT 'Partition Field' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 1 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Model';

CREATE TABLE design_field(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    external_id BIGINT(32)    COMMENT 'External ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    env_id BIGINT(32)    COMMENT 'Env ID' ,
    label_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label Name' ,
    field_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Name' ,
    column_name VARCHAR(64)   DEFAULT '' COMMENT 'Column Name' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    model_id BIGINT(32)    COMMENT 'Model ID' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    field_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Type' ,
    option_set_code VARCHAR(64)   DEFAULT '' COMMENT 'Option Set Code;Set when FieldType is Option or MultiOption' ,
    related_model VARCHAR(64)   DEFAULT '' COMMENT 'Related Model' ,
    related_field VARCHAR(64)   DEFAULT '' COMMENT 'Related Field' ,
    joint_model VARCHAR(64)    COMMENT 'Joint Model' ,
    joint_left VARCHAR(64)    COMMENT 'Joint Model Left Key' ,
    joint_right VARCHAR(64)    COMMENT 'Joint Model Right Key' ,
    cascaded_field VARCHAR(256)   DEFAULT '' COMMENT 'Cascaded Field' ,
    filters VARCHAR(256)   DEFAULT '' COMMENT 'Filters;Filters for relational fields.' ,
    default_value VARCHAR(256)   DEFAULT '' COMMENT 'Default Value' ,
    length INT(11)   DEFAULT 0 COMMENT 'Length' ,
    scale TINYINT(4)   DEFAULT 0 COMMENT 'Scale' ,
    required TINYINT(1)   DEFAULT 0 COMMENT 'Is Required' ,
    readonly TINYINT(1)   DEFAULT 0 COMMENT 'Is Readonly' ,
    hidden TINYINT(1)   DEFAULT 0 COMMENT 'Hidden' ,
    translatable TINYINT(1)   DEFAULT 0 COMMENT 'Translatable' ,
    non_copyable TINYINT(1)   DEFAULT 0 COMMENT 'Non Copyable' ,
    unsearchable TINYINT(1)   DEFAULT 0 COMMENT 'Unsearchable' ,
    computed TINYINT(1)   DEFAULT 0 COMMENT 'Is Computed' ,
    expression TEXT(20000)    COMMENT 'Expression' ,
    dynamic TINYINT(1)   DEFAULT 0 COMMENT 'Dynamic Field' ,
    encrypted TINYINT(1)   DEFAULT 0 COMMENT 'Is Encrypted' ,
    masking_type VARCHAR(64)    COMMENT 'Masking Type' ,
    widget_type VARCHAR(64)    COMMENT 'Widget Type' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Field';

CREATE TABLE design_option_set(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    external_id BIGINT(32)    COMMENT 'External ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    env_id BIGINT(32)    COMMENT 'Env ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Option Set Name' ,
    option_set_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Option Set Code' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Option Set';

CREATE TABLE design_option_item(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    external_id BIGINT(32)    COMMENT 'External ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    env_id BIGINT(32)    COMMENT 'Env ID' ,
    option_set_id BIGINT(32)    COMMENT 'Option Set ID' ,
    option_set_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Option Set Code' ,
    sequence INT(11) NOT NULL  DEFAULT 1 COMMENT 'Sequence' ,
    item_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Item Code' ,
    item_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Item Name' ,
    parent_item_code VARCHAR(64)   DEFAULT '' COMMENT 'Parent Item Code' ,
    item_color VARCHAR(64)   DEFAULT '' COMMENT 'Item Color' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Option Items';

CREATE TABLE design_config(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    external_id BIGINT(32)    COMMENT 'External ID' ,
    app_id BIGINT(32)   DEFAULT 0 COMMENT 'App ID' ,
    env_id BIGINT(32)    COMMENT 'Env ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Name' ,
    code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Code' ,
    value TEXT NOT NULL   COMMENT 'Value' ,
    value_type VARCHAR(64)   DEFAULT '' COMMENT 'Value Data Type' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Config';

CREATE TABLE design_view(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    external_id BIGINT(32)    COMMENT 'External ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    env_id BIGINT(32)    COMMENT 'Env ID' ,
    model_name VARCHAR(64)   DEFAULT '' COMMENT 'Model Name' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'View Name' ,
    code VARCHAR(64)   DEFAULT '' COMMENT 'View Code' ,
    type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'View Type' ,
    sequence TINYINT(4) NOT NULL  DEFAULT 1 COMMENT 'Sequence' ,
    structure TEXT NOT NULL   COMMENT 'Structure' ,
    default_filter TEXT    COMMENT 'Default Filters;View level default filter.' ,
    default_order TEXT    COMMENT 'Default Order;The default sorting condition at the view level.' ,
    nav_id BIGINT(32)   DEFAULT 0 COMMENT 'Navigation ID' ,
    public_view TINYINT(1)   DEFAULT 1 COMMENT 'Public View' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design View';

CREATE TABLE design_navigation(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    external_id BIGINT(32)    COMMENT 'External ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    env_id BIGINT(32)    COMMENT 'Env ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Name' ,
    type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Type' ,
    code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Code' ,
    model_name VARCHAR(256)   DEFAULT '' COMMENT 'Model Name' ,
    parent_id BIGINT(32)    COMMENT 'Parent Navigation' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    filter VARCHAR(256)   DEFAULT '' COMMENT 'Default filters;The default filters at the menu level.' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Navigation';

CREATE TABLE design_model_index(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    external_id BIGINT(32)    COMMENT 'External ID' ,
    app_id BIGINT(32)   DEFAULT 0 COMMENT 'APP ID' ,
    env_id BIGINT(32)    COMMENT 'Env ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Index Title' ,
    code VARCHAR(64)   DEFAULT '' COMMENT 'Index Code' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    model_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'Model ID' ,
    index_name VARCHAR(64)   DEFAULT '' COMMENT 'Index Name' ,
    index_fields VARCHAR(255)   DEFAULT '' COMMENT 'Index Fields' ,
    unique_index TINYINT(1)   DEFAULT 0 COMMENT 'Is Unique Index' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Model Index';

CREATE TABLE design_model_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    label_name VARCHAR(64)    COMMENT 'Label Name' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Model Translation';

CREATE TABLE design_field_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    label_name VARCHAR(64)    COMMENT 'Label Name' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Field Translation';

CREATE TABLE design_option_set_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    name VARCHAR(64)    COMMENT 'Option Set Name' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Option Set Translation';

CREATE TABLE design_option_item_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    item_name VARCHAR(64)    COMMENT 'Item Name' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Option Items Translation';

CREATE TABLE design_model_validation(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    external_id BIGINT(32)    COMMENT 'External ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    env_id BIGINT(32)    COMMENT 'Env ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Name' ,
    code VARCHAR(64)   DEFAULT '' COMMENT 'Code' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    priority TINYINT(4) NOT NULL  DEFAULT 1 COMMENT 'priority' ,
    expression VARCHAR(1000) NOT NULL  DEFAULT '' COMMENT 'Expression' ,
    exception_msg VARCHAR(256) NOT NULL  DEFAULT '' COMMENT 'Exception Message' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)    COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Model Validation;模型数据创建、更新、删除时的同步校验规则';

CREATE TABLE design_model_onchange(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    external_id BIGINT(32)    COMMENT 'External ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    env_id BIGINT(32)    COMMENT 'Env ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Name' ,
    code VARCHAR(64)   DEFAULT '' COMMENT 'Code' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    onchange_fields VARCHAR(255) NOT NULL  DEFAULT '' COMMENT 'Onchange Fields' ,
    expression VARCHAR(1000) NOT NULL  DEFAULT '' COMMENT 'Expression' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(32)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(32)    COMMENT 'Updated By' ,
    deleted TINYINT(1)    COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Model Onchange Event;页面元素的字段变更事件';

CREATE TABLE ai_model(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Code' ,
    model_provider VARCHAR(64)   DEFAULT '' COMMENT 'Model Provider' ,
    model_type VARCHAR(64)   DEFAULT '' COMMENT 'Model Type' ,
    unit_price_input DECIMAL(32,8)   DEFAULT 0 COMMENT 'Input Price/1M tokens;Prompt' ,
    unit_price_output DECIMAL(32,8)   DEFAULT 0 COMMENT 'Output price/1M tokens;Completion' ,
    max_tokens INT(11)   DEFAULT 0 COMMENT 'Max Context Tokens' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'AI Model';

CREATE TABLE ai_robot(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Robot Name' ,
    code VARCHAR(64)   DEFAULT '' COMMENT 'Robot Code' ,
    ai_model_id BIGINT(32) NOT NULL   COMMENT 'AI Model ID' ,
    ai_model VARCHAR(64)   DEFAULT '' COMMENT 'AI Model Code' ,
    ai_provider VARCHAR(64)   DEFAULT '' COMMENT 'AI Provider' ,
    system_prompt TEXT(20000)    COMMENT 'system_prompt' ,
    model_max_tokens INT(11)    COMMENT 'Model Max Context Tokens' ,
    input_tokens_limit INT(11)    COMMENT 'Input Tokens Limit' ,
    output_tokens_limit INT(11)    COMMENT 'Output Tokens Limit' ,
    temperature DOUBLE(24,2)   DEFAULT 0 COMMENT 'Temperature;From 0 to 1' ,
    stream TINYINT(1)   DEFAULT 0 COMMENT 'Enable Stream Output' ,
    presence_penalty DOUBLE(24,2)   DEFAULT 0 COMMENT 'Presence Penalty;From -2 to 2. The larger the value, the less the repetition.' ,
    frequency_penalty DOUBLE(24,2)   DEFAULT 0 COMMENT 'Frequency Penalty;From -2 to 2. The larger the value, the more the dispersion.' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'AI Robot;Robots that pre-define system prompts and parameter configurations.';

CREATE TABLE ai_conversation(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    title VARCHAR(64)   DEFAULT '' COMMENT 'Conversation Title' ,
    robot_id BIGINT(32)    COMMENT 'Robot ID' ,
    total_tokens INT(11)   DEFAULT 0 COMMENT 'Total Tokens' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'AI Conversation';

CREATE TABLE ai_message(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    robot_id BIGINT(32)    COMMENT 'Robot ID' ,
    conversation_id BIGINT(32) NOT NULL   COMMENT 'Conversation ID' ,
    role VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Role' ,
    content TEXT(20000)    COMMENT 'Query Content' ,
    tokens INT(11)   DEFAULT 0 COMMENT 'Tokens' ,
    stream TINYINT(1)    COMMENT 'Stream Output' ,
    parent_id BIGINT(32)    COMMENT 'Parent Message ID' ,
    status VARCHAR(64)    COMMENT 'Status' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'AI Message';

CREATE TABLE ai_feedback(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    conversation_id BIGINT(32) NOT NULL   COMMENT 'Conversation ID' ,
    message_id BIGINT(32) NOT NULL   COMMENT 'Message ID' ,
    feedback VARCHAR(256)   DEFAULT '' COMMENT 'Feedback Content' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'AI Response Feedback';

CREATE TABLE service_record(
    id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'ID' ,
    user_id VARCHAR(32)    COMMENT 'User' ,
    service_id VARCHAR(32)    COMMENT 'Service Product' ,
    order_id BIGINT(32)    COMMENT 'Order ID' ,
    request_data TEXT NOT NULL   COMMENT 'Request Data' ,
    result_summary VARCHAR(3000)    COMMENT 'Result Summary' ,
    result_detail TEXT(20000)    COMMENT 'Result Detail' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '服务记录';

CREATE TABLE service_product(
    id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'ID' ,
    name VARCHAR(32)    COMMENT 'Service Name' ,
    description TEXT(20000)    COMMENT 'Service Description' ,
    category VARCHAR(64)    COMMENT 'Service Category' ,
    price DECIMAL(32,8)    COMMENT 'Price($)' ,
    duration INT(11)    COMMENT 'Service Duration(mins)' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)    COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '服务产品';

CREATE TABLE service_order(
    id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'ID' ,
    user_id VARCHAR(32)    COMMENT 'User' ,
    service_id VARCHAR(32)    COMMENT 'Service Product' ,
    order_number VARCHAR(32)    COMMENT 'Order Number' ,
    order_status VARCHAR(64)    COMMENT 'Order Status' ,
    amount DECIMAL(32,8)    COMMENT 'Amount' ,
    notes VARCHAR(1000)    COMMENT 'Notes' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '服务订单';

CREATE TABLE payment_record(
    id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'ID' ,
    order_id BIGINT(32)    COMMENT 'Order ID' ,
    payment_method VARCHAR(64)    COMMENT 'Payment Method' ,
    payment_status VARCHAR(64)    COMMENT 'Payment Status' ,
    paid_amount DECIMAL(32,8)    COMMENT 'Paid Amount' ,
    paid_at DATETIME    COMMENT 'Paid At' ,
    transaction_id VARCHAR(64)    COMMENT 'Transaction ID;Third party transaction number' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = '支付记录';

CREATE TABLE user_login_history(
    id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'ID' ,
    tenant_id VARCHAR(32)    COMMENT 'Tenant ID' ,
    user_id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'User ID' ,
    login_method VARCHAR(64)    COMMENT 'Login Method' ,
    login_device_type VARCHAR(64)    COMMENT 'Login Device Type' ,
    ip_address VARCHAR(64)    COMMENT 'IP Address' ,
    user_agent VARCHAR(64)    COMMENT 'User Agent' ,
    location VARCHAR(64)    COMMENT 'Location' ,
    status VARCHAR(64)    COMMENT 'Status' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'User Login History';

CREATE TABLE user_account(
    id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'ID' ,
    tenant_id VARCHAR(32)    COMMENT 'Tenant ID' ,
    nickname VARCHAR(64)    COMMENT 'Nickname' ,
    username VARCHAR(64)    COMMENT 'Username' ,
    password VARCHAR(256)    COMMENT 'Password' ,
    password_salt VARCHAR(64)    COMMENT 'Password Salt' ,
    email VARCHAR(64)    COMMENT 'Email' ,
    mobile VARCHAR(64)    COMMENT 'Mobile' ,
    activation_time DATETIME    COMMENT 'Activation Time' ,
    policy_id VARCHAR(32)    COMMENT 'Policy ID' ,
    status VARCHAR(64)   DEFAULT 'Active' COMMENT 'Account Status' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'User Account';

CREATE TABLE user_profile(
    id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'ID' ,
    tenant_id VARCHAR(32)    COMMENT 'Tenant ID' ,
    user_id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'User ID' ,
    full_name VARCHAR(64)    COMMENT 'Full Name' ,
    chinese_name VARCHAR(64)    COMMENT 'Chinese Name' ,
    birth_date DATE    COMMENT 'Birth Date' ,
    birth_time TIME    COMMENT 'Birth Time' ,
    birth_city VARCHAR(64)    COMMENT 'Birth City' ,
    gender VARCHAR(64)    COMMENT 'Gender' ,
    photo VARCHAR(32)    COMMENT 'Profile Photo' ,
    language VARCHAR(64)    COMMENT 'Language' ,
    timezone VARCHAR(64)    COMMENT 'Timezone' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'User Profile';

CREATE TABLE user_auth_provider(
    id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'ID' ,
    tenant_id VARCHAR(32)    COMMENT 'Tenant ID' ,
    user_id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'User ID' ,
    provider VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Provider' ,
    provider_user_id VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Provider User ID' ,
    additional_info VARCHAR(1000)    COMMENT 'Additional Info' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'User Auth Provider';

CREATE TABLE user_auth_failure(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    user_id VARCHAR(32)    COMMENT 'User ID' ,
    request_params TEXT    COMMENT 'Request Params' ,
    failure_reason VARCHAR(1000)    COMMENT 'Failure Reason' ,
    error_stack TEXT(20000)    COMMENT 'Error Stack' ,
    ip_address VARCHAR(64)    COMMENT 'IP Address' ,
    user_agent VARCHAR(64)    COMMENT 'User Agent' ,
    location VARCHAR(64)    COMMENT 'Location' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'User Auth Failure';

CREATE TABLE user_security_policy(
    id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'ID' ,
    tenant_id BIGINT(32)    COMMENT 'Tenant ID' ,
    name VARCHAR(64)    COMMENT 'Policy Name' ,
    code VARCHAR(64)    COMMENT 'Policy Code' ,
    login_methods VARCHAR(255)    COMMENT 'Login Methods' ,
    active_device_limit INT(11)    COMMENT 'Active Device Limit' ,
    session_duration INT(11)    COMMENT 'Server Session Duration' ,
    session_idle_duration INT(11)    COMMENT 'Client Cookie-Session Idle Duration' ,
    force_change_initial_password TINYINT(1)    COMMENT 'Force Change Initial Password' ,
    password_valid_days INT(11)    COMMENT 'Password Valid Days' ,
    password_retry_interval INT(11)    COMMENT 'Password Retry Interval' ,
    password_retry_limit INT(11)    COMMENT 'Password Retry Limit' ,
    password_complexity_prompt VARCHAR(128)    COMMENT 'Password Complexity Prompt' ,
    password_not_duplicate INT(11)    COMMENT 'Passwords Not Duplicate;Number of recent passwords that do not duplicate' ,
    min_length INT(11)    COMMENT 'Minimum Character Length' ,
    min_lowercase INT(11)    COMMENT 'Minimum Lowercase Characters' ,
    min_uppercase INT(11)    COMMENT 'Minimum Uppercase Characters' ,
    min_digits INT(11)    COMMENT 'Minimum Digits' ,
    min_modified_chars INT(11)    COMMENT 'Minimum Modified Characters' ,
    min_special_chars INT(11)    COMMENT 'Minimum Special Characters' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'User Security Policy';

