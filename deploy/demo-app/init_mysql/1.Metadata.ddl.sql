CREATE TABLE sys_model(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_code VARCHAR(64)    COMMENT 'App Code' ,
    label VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    display_name VARCHAR(256)   DEFAULT '' COMMENT 'Display Name' ,
    search_name VARCHAR(256)   DEFAULT '' COMMENT 'Search Name' ,
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
    copyable TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Copyable' ,
    data_source VARCHAR(64)    COMMENT 'Data Source' ,
    service_name VARCHAR(64)    COMMENT 'Service Name' ,
    business_key VARCHAR(256)    COMMENT 'Business Primary Key' ,
    partition_field VARCHAR(64)   DEFAULT '' COMMENT 'Partition Field' ,
    description VARCHAR(512)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Model';


ALTER TABLE sys_model ADD UNIQUE INDEX uniq_modelname (model_name);

CREATE TABLE sys_field_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    label VARCHAR(64)    COMMENT 'Label' ,
    description VARCHAR(512)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Field Translation';

CREATE TABLE sys_model_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    label VARCHAR(64)    COMMENT 'Label' ,
    description VARCHAR(512)    COMMENT 'Description' ,
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
    app_code VARCHAR(64)    COMMENT 'App Code' ,
    label VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label' ,
    field_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Name' ,
    column_name VARCHAR(64)   DEFAULT '' COMMENT 'Column Name' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    model_id BIGINT(32)    COMMENT 'Model ID' ,
    description VARCHAR(512)   DEFAULT '' COMMENT 'Description' ,
    field_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Type' ,
    option_set_code VARCHAR(64)   DEFAULT '' COMMENT 'Option Set Code;Set when FieldType is Option or MultiOption' ,
    related_model VARCHAR(64)   DEFAULT '' COMMENT 'Related Model' ,
    related_field VARCHAR(64)   DEFAULT '' COMMENT 'Related Field' ,
    join_model VARCHAR(64)    COMMENT 'Join Model' ,
    join_left VARCHAR(64)    COMMENT 'Join Model Left Key' ,
    join_right VARCHAR(64)    COMMENT 'Join Model Right Key' ,
    cascaded_field VARCHAR(256)   DEFAULT '' COMMENT 'Cascaded Field' ,
    filters VARCHAR(256)   DEFAULT '' COMMENT 'Filters;Filters for relational fields.' ,
    default_value VARCHAR(256)   DEFAULT '' COMMENT 'Default Value' ,
    length INT(11)   DEFAULT 0 COMMENT 'Length' ,
    scale TINYINT(4)   DEFAULT 0 COMMENT 'Scale' ,
    required TINYINT(1)   DEFAULT 0 COMMENT 'Is Required' ,
    readonly TINYINT(1)   DEFAULT 0 COMMENT 'Is Readonly' ,
    hidden TINYINT(1)   DEFAULT 0 COMMENT 'Hidden' ,
    translatable TINYINT(1)   DEFAULT 0 COMMENT 'Translatable' ,
    copyable TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Copyable' ,
    unsearchable TINYINT(1)   DEFAULT 0 COMMENT 'Unsearchable' ,
    computed TINYINT(1)   DEFAULT 0 COMMENT 'Is Computed' ,
    expression TEXT(20000)    COMMENT 'Expression' ,
    dynamic TINYINT(1)   DEFAULT 0 COMMENT 'Dynamic Field' ,
    encrypted TINYINT(1)   DEFAULT 0 COMMENT 'Is Encrypted' ,
    auto_sequence TINYINT(1)   DEFAULT 0 COMMENT 'Auto Sequence;Auto-fill from a sequence on INSERT when blank' ,
    masking_type VARCHAR(64)    COMMENT 'Masking Type' ,
    widget_type VARCHAR(64)    COMMENT 'Widget Type' ,
    related_field_type VARCHAR(64)    COMMENT 'Resolved physical type of a TO_ONE FK referenced column (system-computed)' ,
    on_delete VARCHAR(32)    COMMENT 'TO_ONE FK delete strategy (Restrict/Cascade/SetNull); NULL = KEEP.' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Field';


ALTER TABLE sys_field ADD UNIQUE INDEX uniq_modelname_fieldname (model_name,field_name);

CREATE TABLE sys_option_set(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_code VARCHAR(64)    COMMENT 'App Code' ,
    label VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label' ,
    option_set_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Option Set Code' ,
    description VARCHAR(512)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Option Set';


ALTER TABLE sys_option_set ADD UNIQUE INDEX unique_sys_option_set_code (option_set_code);

CREATE TABLE sys_option_set_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    label VARCHAR(64)    COMMENT 'Label' ,
    description VARCHAR(512)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Option Set Translation';

CREATE TABLE sys_option_item(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_code VARCHAR(64)    COMMENT 'App Code' ,
    option_set_id BIGINT(32)    COMMENT 'Option Set ID' ,
    option_set_code VARCHAR(64)   DEFAULT '' COMMENT 'Option Set Code' ,
    sequence INT(11)   DEFAULT 1 COMMENT 'Sequence' ,
    item_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Item Code' ,
    label VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label' ,
    parent_item_code VARCHAR(64)   DEFAULT '' COMMENT 'Parent Item Code' ,
    item_tone VARCHAR(64)   DEFAULT '' COMMENT 'Item Tone' ,
    item_icon VARCHAR(64)   DEFAULT '' COMMENT 'Item Icon' ,
    description VARCHAR(512)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Option Items';

CREATE TABLE sys_option_item_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    label VARCHAR(64)    COMMENT 'Label' ,
    description VARCHAR(512)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Option Items Translation';

CREATE TABLE sys_model_index(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_code VARCHAR(64)    COMMENT 'App Code' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    model_id BIGINT(32)    COMMENT 'Model ID' ,
    index_name VARCHAR(60)   DEFAULT '' COMMENT 'Index Name' ,
    index_fields VARCHAR(256)   DEFAULT '' COMMENT 'Index Fields' ,
    unique_index TINYINT(1)   DEFAULT 0 COMMENT 'Is Unique Index' ,
    message VARCHAR(256)   DEFAULT NULL COMMENT 'Unique-violation message' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Model Index';

CREATE TABLE sys_config(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_code VARCHAR(64)    COMMENT 'App Code' ,
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
    app_code VARCHAR(64)    COMMENT 'App Code' ,
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
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
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
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'User Default View';

CREATE TABLE import_template(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    tenant_id VARCHAR(32)    COMMENT 'Tenant ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Template Name' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    import_rule VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Import Rule' ,
    unique_constraints VARCHAR(256)    COMMENT 'Unique Constraints' ,
    ignore_empty TINYINT(1)   DEFAULT true COMMENT 'Ignore Empty Value' ,
    skip_exception TINYINT(1)   DEFAULT true COMMENT 'Skip Abnormal Data' ,
    custom_handler VARCHAR(128)    COMMENT 'Custom Import Handler;The simpleName of the CustomImportHandler interface implementation class' ,
    sync_import TINYINT(1)    COMMENT 'Synchronous Import;Default is asynchronous import via MQ.' ,
    include_description TINYINT(1)    COMMENT 'Include Import Description' ,
    description VARCHAR(1000)    COMMENT 'Description' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
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
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'File Record';


ALTER TABLE file_record ADD UNIQUE INDEX uniq_oss_key (oss_key);

CREATE TABLE import_history(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    tenant_id VARCHAR(32)    COMMENT 'Tenant ID' ,
    template_id BIGINT(32)    COMMENT 'Template ID' ,
    model_name VARCHAR(64)    COMMENT 'Model Name' ,
    original_file_id BIGINT(32)    COMMENT 'Original File ID' ,
    import_type VARCHAR(64)    COMMENT 'Import Type' ,
    import_rule VARCHAR(64)    COMMENT 'Import Rule' ,
    status VARCHAR(64)    COMMENT 'Import Status' ,
    failed_file_id BIGINT(32)    COMMENT 'Failed File ID' ,
    total_rows INT(11)   DEFAULT 0 COMMENT 'Total Rows' ,
    success_rows INT(11)   DEFAULT 0 COMMENT 'Success Rows' ,
    failed_rows INT(11)   DEFAULT 0 COMMENT 'Failed Rows' ,
    duration DOUBLE(24,2)   DEFAULT 0 COMMENT 'Duration(s)' ,
    error_message VARCHAR(1000)    COMMENT 'Error Message' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
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
    custom_file_template TINYINT(1)    COMMENT 'Custom File Template' ,
    file_id BIGINT(32)    COMMENT 'File Template ID' ,
    filters VARCHAR(256)    COMMENT 'Filters' ,
    orders VARCHAR(256)    COMMENT 'Orders;The orders of the exported data.' ,
    custom_handler VARCHAR(128)    COMMENT 'Custom Export Handler;The simpleName of the CustomExportHandler interface implementation class' ,
    enable_transpose TINYINT(1)    COMMENT 'Enable Transpose;Transpose the data filled in the file template.' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Export Template';

CREATE TABLE export_history(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    tenant_id VARCHAR(32)    COMMENT 'Tenant ID' ,
    template_id BIGINT(32)    COMMENT 'Template ID' ,
    model_name VARCHAR(64)    COMMENT 'Model Name' ,
    exported_file_id BIGINT(32) NOT NULL   COMMENT 'Exported File ID' ,
    total_rows INT(11)   DEFAULT 0 COMMENT 'Total Rows' ,
    duration DOUBLE(24,2)   DEFAULT 0 COMMENT 'Duration(s)' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Export History';

CREATE TABLE import_template_field(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    template_id BIGINT(32)    COMMENT 'Import Template ID' ,
    field_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Name;Cascading fields are supported' ,
    custom_header VARCHAR(64)    COMMENT 'Custom Header;Custom header for imported field' ,
    sequence INT(11)   DEFAULT 1 COMMENT 'Sequence' ,
    required TINYINT(1)    COMMENT 'Required' ,
    default_value VARCHAR(128)    COMMENT 'Default Value;Support string interpolation calculation `#{}`.' ,
    description VARCHAR(256)    COMMENT 'Description;Field introduction or import-related help and tips.' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Import Template Fields';

CREATE TABLE export_template_field(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    template_id BIGINT(32)    COMMENT 'Export Template ID' ,
    field_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Name;Cascading fields are supported' ,
    custom_header VARCHAR(64)    COMMENT 'Custom Header;Custom header for exported field' ,
    sequence INT(11)   DEFAULT 1 COMMENT 'Sequence' ,
    ignored TINYINT(1)    COMMENT 'Ignored In File;Only used in custom handler' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
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
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Flow Node';

CREATE TABLE sys_cron(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    name VARCHAR(64)   DEFAULT '' COMMENT 'Cron Job Name' ,
    cron_expression VARCHAR(64)   DEFAULT '' COMMENT 'Cron Expression;Use Quartz seven-field format with seconds and years.' ,
    cron_semantic VARCHAR(64)   DEFAULT '' COMMENT 'Semantic Description' ,
    limit_execution TINYINT(1)    COMMENT 'Limit the Execution Times' ,
    remaining_count TINYINT(4)   DEFAULT -1 COMMENT 'Remaining Execution Times;Subtract 1 after each execution. If the value is less than 1, no more execution is performed. Clear the next execution time and set Enable to false.' ,
    next_exec_time DATETIME    COMMENT 'Next Execution Time;Recalculated and updated after each successful execution. Allows rollback compensation runs.' ,
    last_exec_time DATETIME    COMMENT 'Last Execution Time;Records the execution start time after each successful execution and allows rollback compensation for changed data.' ,
    redo_misfire TINYINT(1)   DEFAULT 0 COMMENT 'Redo Missed Task;No compensation by default. When true, compensate immediately only once.' ,
    priority TINYINT(4)   DEFAULT 1 COMMENT 'Priority;Smaller numbers indicate higher priority, from 0 to 10' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    tenant_job_mode VARCHAR(64)    COMMENT 'Tenant job execution mode: PER_TENANT or CROSS_TENANT' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Cron;Carries the task ownership time during execution and supports automatic compensation for missed tasks.';

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
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
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
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
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
    version INT NOT NULL DEFAULT 1 COMMENT 'Version' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
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
    created_by VARCHAR(64)    COMMENT 'Created By' ,
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
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Flow Edge';


ALTER TABLE flow_edge ADD UNIQUE INDEX unique_source_target (source_id,target_id);

CREATE TABLE sys_model_onchange(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Name' ,
    code VARCHAR(64)   DEFAULT '' COMMENT 'Code' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    onchange_fields VARCHAR(256) NOT NULL  DEFAULT '' COMMENT 'Onchange Fields' ,
    expression VARCHAR(1000) NOT NULL  DEFAULT '' COMMENT 'Expression' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Model Onchange Event';

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
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Model Validation;';

CREATE TABLE flow_stage(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    flow_id BIGINT(32) NOT NULL   COMMENT 'Flow ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Stage Name' ,
    description VARCHAR(256)    COMMENT 'Stage Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
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
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Flow Event';

CREATE TABLE design_app(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    owner_id BIGINT(32)    COMMENT 'Owner' ,
    app_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'App Name' ,
    app_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'App Code' ,
    app_type VARCHAR(64)   DEFAULT '' COMMENT 'App Type' ,
    package_name VARCHAR(64)   DEFAULT '' COMMENT 'Package Name;Fill in when you need to generate code, the model in the same App belongs to the same Module.' ,
    status VARCHAR(64)   DEFAULT 'Active' COMMENT 'App Status' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design App';

CREATE TABLE design_app_env(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32) NOT NULL  DEFAULT 0 COMMENT 'App ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Env Name' ,
    sequence INT(11)    COMMENT 'Sequence' ,
    env_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Env Type' ,
    env_status VARCHAR(64) NOT NULL DEFAULT 'Stable' COMMENT 'Env runtime status — deployment mutex (Stable / Deploying)' ,
    database_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Target runtime database flavor (moved here from DesignApp)' ,
    connector_type VARCHAR(64)    COMMENT 'Connector kind to the target — Softa runtime or raw JDBC' ,
    jdbc_url VARCHAR(256)   DEFAULT '' COMMENT 'Raw JDBC connection URL when connectorType = JDBC' ,
    jdbc_username VARCHAR(128)   DEFAULT '' COMMENT 'JDBC Username' ,
    jdbc_password VARCHAR(512)   DEFAULT '' COMMENT 'JDBC Password;ORM-layer encrypted at rest' ,
    protected_env TINYINT(1)    COMMENT 'Protected Env' ,
    active TINYINT(1)    DEFAULT 1 COMMENT 'Active' ,
    upgrade_endpoint VARCHAR(128)   DEFAULT '' COMMENT 'Upgrade API EndPoint (mandatory only for connectorType = SOFTA)' ,
    public_key VARCHAR(256)   DEFAULT '' COMMENT 'Public Key;Base64-encoded X.509 SubjectPublicKeyInfo' ,
    private_key VARCHAR(512)   DEFAULT '' COMMENT 'Private Key;Base64-encoded PKCS#8, ORM-layer encrypted at rest' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    version BIGINT(32) NOT NULL DEFAULT 1 COMMENT 'Optimistic-lock version guarding the env-status mutex' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design App Env';

CREATE TABLE design_activity(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32) NOT NULL   COMMENT 'App ID' ,
    env_id BIGINT(32) NOT NULL   COMMENT 'The environment this activity targets' ,
    kind VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'PUBLISH (design->runtime) / IMPORT (runtime->design) / MERGE (env->env)' ,
    status VARCHAR(64)    COMMENT 'Status' ,
    source_env_id BIGINT(32)    COMMENT 'MERGE only: the env whose design was merged from' ,
    operator_id BIGINT(32)    COMMENT 'Operator' ,
    started_time DATETIME    COMMENT 'Started Time' ,
    finished_time DATETIME    COMMENT 'Finished Time' ,
    change_set MEDIUMTEXT    COMMENT 'The per-row business-key change set applied (PUBLISH / MERGE)' ,
    detail MEDIUMTEXT    COMMENT 'Kind-specific audit: rendered DDL + per-statement results (PUBLISH), counts (MERGE)' ,
    snapshot_id BIGINT(32)    COMMENT 'The post-operation DesignSnapshot this activity produced (restore source)' ,
    error_message TEXT(20000)    COMMENT 'Error Message' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Activity';

CREATE TABLE design_snapshot(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    activity_id BIGINT(32) NOT NULL   COMMENT 'The DesignActivity that produced this snapshot' ,
    content MEDIUMTEXT    COMMENT 'Full per-env design set (DesignRows) captured after the activity' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id),
    UNIQUE KEY uk_design_snapshot_activity (activity_id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Snapshot';

CREATE TABLE design_field_db_mapping(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    field_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Type' ,
    database_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Database Type' ,
    column_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Column Type' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Field DB Mapping;Mapping between the FieldType and the column type of the database.';

CREATE TABLE design_field_domain(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Domain name — the business key fields reference' ,
    field_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Type' ,
    widget_type VARCHAR(64)    COMMENT 'UI widget hint (dbType-agnostic)' ,
    default_value VARCHAR(64)    COMMENT 'Default Value' ,
    length INT(11)    COMMENT 'Length' ,
    scale INT(11)    COMMENT 'Scale' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id),
    UNIQUE KEY uk_design_field_domain_name (name)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Field Domain (renamed from design_field_type_default)';

CREATE TABLE design_sql_template(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    database_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Database Type' ,
    create_table_template TEXT(20000) NOT NULL   COMMENT 'Create Table Template' ,
    alter_index_template TEXT(20000) NOT NULL   COMMENT 'Alter Index Template' ,
    alter_table_template TEXT(20000) NOT NULL   COMMENT 'Alter Table Template' ,
    drop_table_template TEXT(20000) NOT NULL   COMMENT 'Drop Table Template' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design SQL Template';

CREATE TABLE design_model(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    env_id BIGINT(32) NOT NULL   COMMENT 'Env ID' ,
    label VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    display_name VARCHAR(256)   DEFAULT '' COMMENT 'Display Name' ,
    search_name VARCHAR(256)   DEFAULT '' COMMENT 'Search Name' ,
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
    copyable TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Copyable' ,
    data_source VARCHAR(64)    COMMENT 'Data Source' ,
    service_name VARCHAR(64)    COMMENT 'Service Name' ,
    business_key VARCHAR(256)    COMMENT 'Business Primary Key' ,
    partition_field VARCHAR(64)   DEFAULT '' COMMENT 'Partition Field' ,
    description VARCHAR(512)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 1 COMMENT 'Deleted' ,
    PRIMARY KEY (id),
    UNIQUE KEY uk_design_model_env_name (env_id, model_name)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Model';

CREATE TABLE design_field(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    label VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label' ,
    field_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Name' ,
    column_name VARCHAR(64)   DEFAULT '' COMMENT 'Column Name' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    model_id BIGINT(32)    COMMENT 'Model ID' ,
    env_id BIGINT(32) NOT NULL   COMMENT 'Env ID' ,
    description VARCHAR(512)   DEFAULT '' COMMENT 'Description' ,
    field_type VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Field Type' ,
    option_set_code VARCHAR(64)   DEFAULT '' COMMENT 'Option Set Code;Set when FieldType is Option or MultiOption' ,
    related_model VARCHAR(64)   DEFAULT '' COMMENT 'Related Model' ,
    related_field VARCHAR(64)   DEFAULT '' COMMENT 'Related Field' ,
    join_model VARCHAR(64)    COMMENT 'Join Model' ,
    join_left VARCHAR(64)    COMMENT 'Join Model Left Key' ,
    join_right VARCHAR(64)    COMMENT 'Join Model Right Key' ,
    cascaded_field VARCHAR(256)   DEFAULT '' COMMENT 'Cascaded Field' ,
    filters VARCHAR(256)   DEFAULT '' COMMENT 'Filters;Filters for relational fields.' ,
    default_value VARCHAR(256)   DEFAULT '' COMMENT 'Default Value' ,
    length INT(11)   DEFAULT 0 COMMENT 'Length' ,
    scale TINYINT(4)   DEFAULT 0 COMMENT 'Scale' ,
    required TINYINT(1)   DEFAULT 0 COMMENT 'Is Required' ,
    readonly TINYINT(1)   DEFAULT 0 COMMENT 'Is Readonly' ,
    hidden TINYINT(1)   DEFAULT 0 COMMENT 'Hidden' ,
    translatable TINYINT(1)   DEFAULT 0 COMMENT 'Translatable' ,
    copyable TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Copyable' ,
    unsearchable TINYINT(1)   DEFAULT 0 COMMENT 'Unsearchable' ,
    computed TINYINT(1)   DEFAULT 0 COMMENT 'Is Computed' ,
    expression TEXT(20000)    COMMENT 'Expression' ,
    dynamic TINYINT(1)   DEFAULT 0 COMMENT 'Dynamic Field' ,
    encrypted TINYINT(1)   DEFAULT 0 COMMENT 'Is Encrypted' ,
    auto_sequence TINYINT(1)   DEFAULT 0 COMMENT 'Auto Sequence;Auto-fill from a sequence on INSERT when blank' ,
    masking_type VARCHAR(64)    COMMENT 'Masking Type' ,
    widget_type VARCHAR(64)    COMMENT 'Widget Type' ,
    related_field_type VARCHAR(64)    COMMENT 'Resolved physical type of a TO_ONE FK referenced column (system-computed)' ,
    on_delete VARCHAR(32)    COMMENT 'TO_ONE FK delete strategy (Restrict/Cascade/SetNull); NULL = KEEP.' ,
    domain_id BIGINT(32)    COMMENT 'DesignFieldDomain applied as a one-time template — design-time provenance only' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id),
    UNIQUE KEY uk_design_field_env_model_field (env_id, model_name, field_name)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Field';

CREATE TABLE design_option_set(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    env_id BIGINT(32) NOT NULL   COMMENT 'Env ID' ,
    label VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label' ,
    option_set_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Option Set Code' ,
    description VARCHAR(512)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id),
    UNIQUE KEY uk_design_option_set_env_code (env_id, option_set_code)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Option Set';

CREATE TABLE design_option_item(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    option_set_id BIGINT(32)    COMMENT 'Option Set ID' ,
    env_id BIGINT(32) NOT NULL   COMMENT 'Env ID' ,
    option_set_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Option Set Code' ,
    sequence INT(11) NOT NULL  DEFAULT 1 COMMENT 'Sequence' ,
    item_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Item Code' ,
    label VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label' ,
    parent_item_code VARCHAR(64)   DEFAULT '' COMMENT 'Parent Item Code' ,
    item_tone VARCHAR(64)   DEFAULT '' COMMENT 'Item Tone' ,
    item_icon VARCHAR(64)   DEFAULT '' COMMENT 'Item Icon' ,
    description VARCHAR(512)   DEFAULT '' COMMENT 'Description' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id),
    UNIQUE KEY uk_design_option_item_env_set_item (env_id, option_set_code, item_code)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Option Items';

CREATE TABLE design_view(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
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
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design View';

CREATE TABLE design_model_index(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    app_id BIGINT(32)   DEFAULT 0 COMMENT 'APP ID' ,
    model_name VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Model Name' ,
    model_id BIGINT(32)    COMMENT 'Model ID' ,
    env_id BIGINT(32) NOT NULL   COMMENT 'Env ID' ,
    index_name VARCHAR(60)   DEFAULT '' COMMENT 'Index Name' ,
    index_fields VARCHAR(256)   DEFAULT '' COMMENT 'Index Fields' ,
    unique_index TINYINT(1)   DEFAULT 0 COMMENT 'Is Unique Index' ,
    message VARCHAR(256)   DEFAULT NULL COMMENT 'Unique-violation message' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id),
    UNIQUE KEY uk_design_model_index_env_model_index (env_id, model_name, index_name)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Model Index';

CREATE TABLE design_model_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    label VARCHAR(64)    COMMENT 'Label' ,
    description VARCHAR(512)    COMMENT 'Description' ,
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
    label VARCHAR(64)    COMMENT 'Label' ,
    description VARCHAR(512)    COMMENT 'Description' ,
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
    label VARCHAR(64)    COMMENT 'Label' ,
    description VARCHAR(512)    COMMENT 'Description' ,
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
    label VARCHAR(64)    COMMENT 'Label' ,
    description VARCHAR(512)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Design Option Items Translation';

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
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
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
    output_tokens_limit INT(11)    COMMENT 'Output Tokens Limit' ,
    temperature DOUBLE(24,2)   DEFAULT 0 COMMENT 'Temperature;From 0 to 1' ,
    stream TINYINT(1)   DEFAULT 0 COMMENT 'Enable Stream Output' ,
    presence_penalty DOUBLE(24,2)   DEFAULT 0 COMMENT 'Presence Penalty;From -2 to 2. The larger the value, the less the repetition.' ,
    frequency_penalty DOUBLE(24,2)   DEFAULT 0 COMMENT 'Frequency Penalty;From -2 to 2. The larger the value, the more the dispersion.' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'AI Robot;Robots that pre-define system prompts and parameter configurations.';

CREATE TABLE ai_conversation(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    title VARCHAR(64)   DEFAULT '' COMMENT 'Conversation Title' ,
    robot_id BIGINT(32)    COMMENT 'Robot ID' ,
    input_tokens INT(11)   DEFAULT 0 COMMENT 'Input Tokens' ,
    output_tokens INT(11)   DEFAULT 0 COMMENT 'Output Tokens' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'AI Conversation';

CREATE TABLE ai_message(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    robot_id BIGINT(32)    COMMENT 'Robot ID' ,
    conversation_id BIGINT(32) NOT NULL   COMMENT 'Conversation ID' ,
    role VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Role' ,
    content TEXT(20000)    COMMENT 'Query Content' ,
    input_tokens INT(11)   DEFAULT 0 COMMENT 'Input Tokens' ,
    output_tokens INT(11)   DEFAULT 0 COMMENT 'Output Tokens' ,
    stream TINYINT(1)    COMMENT 'Stream Output' ,
    parent_id BIGINT(32)    COMMENT 'Parent Message ID' ,
    status VARCHAR(64)    COMMENT 'Status' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'AI Message';

CREATE TABLE ai_feedback(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    conversation_id BIGINT(32) NOT NULL   COMMENT 'Conversation ID' ,
    message_id BIGINT(32) NOT NULL   COMMENT 'Message ID' ,
    feedback VARCHAR(256)   DEFAULT '' COMMENT 'Feedback Content' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'AI Response Feedback';

CREATE TABLE user_login_history(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    tenant_id BIGINT(32)    COMMENT 'Tenant ID' ,
    user_id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'User ID' ,
    login_method VARCHAR(64)    COMMENT 'Login Method' ,
    login_device_type VARCHAR(64)    COMMENT 'Login Device Type' ,
    ip_address VARCHAR(64)    COMMENT 'IP Address' ,
    user_agent VARCHAR(64)    COMMENT 'User Agent' ,
    location VARCHAR(64)    COMMENT 'Location' ,
    status VARCHAR(64)    COMMENT 'Status' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'User Login History';

CREATE TABLE user_account(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    tenant_id BIGINT(32)    COMMENT 'Tenant ID' ,
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
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'User Account';

CREATE TABLE user_profile(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    tenant_id BIGINT(32)    COMMENT 'Tenant ID' ,
    user_id BIGINT(32) NOT NULL   COMMENT 'User ID' ,
    full_name VARCHAR(64)    COMMENT 'Full Name' ,
    chinese_name VARCHAR(64)    COMMENT 'Chinese Name' ,
    birth_date DATE    COMMENT 'Birth Date' ,
    birth_time TIME    COMMENT 'Birth Time' ,
    birth_city VARCHAR(64)    COMMENT 'Birth City' ,
    gender VARCHAR(64)    COMMENT 'Gender' ,
    photo BIGINT(32)    COMMENT 'Profile Photo' ,
    language VARCHAR(64)    COMMENT 'Language' ,
    timezone VARCHAR(64)    COMMENT 'Timezone' ,
    density VARCHAR(64)    COMMENT 'Density' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'User Profile';

CREATE TABLE user_auth_provider(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    tenant_id BIGINT(32)    COMMENT 'Tenant ID' ,
    user_id VARCHAR(32) NOT NULL  DEFAULT '' COMMENT 'User ID' ,
    provider VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Provider' ,
    provider_user_id VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Provider User ID' ,
    additional_info VARCHAR(1000)    COMMENT 'Additional Info' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
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
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'User Auth Failure';

CREATE TABLE user_security_policy(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
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
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'User Security Policy';

CREATE TABLE service_record(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    user_id VARCHAR(32)    COMMENT 'User' ,
    service_id VARCHAR(32)    COMMENT 'Service Product' ,
    order_id BIGINT(32)    COMMENT 'Order ID' ,
    request_data TEXT NOT NULL   COMMENT 'Request Data' ,
    result_summary VARCHAR(3000)    COMMENT 'Result Summary' ,
    result_detail TEXT(20000)    COMMENT 'Result Detail' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Service Record';

CREATE TABLE service_product(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    name VARCHAR(32)    COMMENT 'Service Name' ,
    description TEXT(20000)    COMMENT 'Service Description' ,
    category VARCHAR(64)    COMMENT 'Service Category' ,
    price DECIMAL(32,8)    COMMENT 'Price($)' ,
    duration INT(11)    COMMENT 'Service Duration(mins)' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)    COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Service Product';

CREATE TABLE service_order(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    user_id VARCHAR(32)    COMMENT 'User' ,
    service_id VARCHAR(32)    COMMENT 'Service Product' ,
    order_number VARCHAR(32)    COMMENT 'Order Number' ,
    order_status VARCHAR(64)    COMMENT 'Order Status' ,
    amount DECIMAL(32,8)    COMMENT 'Amount' ,
    notes VARCHAR(1000)    COMMENT 'Notes' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Service Order';

CREATE TABLE payment_record(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    order_id BIGINT(32)    COMMENT 'Order ID' ,
    payment_method VARCHAR(64)    COMMENT 'Payment Method' ,
    payment_status VARCHAR(64)    COMMENT 'Payment Status' ,
    paid_amount DECIMAL(32,8)    COMMENT 'Paid Amount' ,
    paid_at DATETIME    COMMENT 'Paid At' ,
    transaction_id VARCHAR(64)    COMMENT 'Transaction ID;Third party transaction number' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Payment Record';

CREATE TABLE tenant_info(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    name VARCHAR(64)    COMMENT 'Name' ,
    code VARCHAR(64)    COMMENT 'Code' ,
    status VARCHAR(64)    COMMENT 'Status' ,
    lifecycle VARCHAR(64)    COMMENT 'Lifecycle Stage' ,
    activated_time DATETIME    COMMENT 'Activated Time' ,
    suspended_time DATETIME    COMMENT 'Suspended Time' ,
    closed_time DATETIME    COMMENT 'Closed Time' ,
    default_language VARCHAR(64)    COMMENT 'Default Language' ,
    default_timezone VARCHAR(64)    COMMENT 'Default Timezone' ,
    default_currency VARCHAR(4)    COMMENT 'Default Currency — ISO 4217 alpha-3; FK to currency.code in reference-data-starter' ,
    default_country VARCHAR(4)    COMMENT 'Default Country — ISO 3166-1 alpha-2; FK to country_region.code in reference-data-starter' ,
    data_region VARCHAR(64)    COMMENT 'Data Region' ,
    plan_id BIGINT(32)    COMMENT 'Plan ID' ,
    subscription_id BIGINT(32)    COMMENT 'Subscription ID' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    deleted TINYINT(1)   DEFAULT 0 COMMENT 'Deleted' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Tenant Info';

CREATE TABLE tenant_option_set(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    tenant_id BIGINT(32)    COMMENT 'Tenant ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    label VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label' ,
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
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Tenant Option Set';


ALTER TABLE tenant_option_set ADD UNIQUE INDEX unique_tenant_option_item (tenant_id,option_set_code);

CREATE TABLE tenant_option_set_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    label VARCHAR(64)    COMMENT 'Label' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Tenant Option Set Translation';

CREATE TABLE tenant_option_item(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    tenant_id BIGINT(32)    COMMENT 'Tenant ID' ,
    app_id BIGINT(32)    COMMENT 'App ID' ,
    option_set_id BIGINT(32)    COMMENT 'Option Set ID' ,
    option_set_code VARCHAR(64)   DEFAULT '' COMMENT 'Option Set Code' ,
    parent_item_id BIGINT(32)    COMMENT 'Parent Item ID' ,
    sequence INT(11)   DEFAULT 1 COMMENT 'Sequence' ,
    item_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Item Code' ,
    label VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Label' ,
    item_tone VARCHAR(64)   DEFAULT '' COMMENT 'Item Tone' ,
    item_icon VARCHAR(64)   DEFAULT '' COMMENT 'Item Icon' ,
    description VARCHAR(256)   DEFAULT '' COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    active TINYINT(1)   DEFAULT 1 COMMENT 'Active' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Tenant Option Items';

CREATE TABLE tenant_option_item_trans(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    language_code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Language Code' ,
    row_id BIGINT(32)    COMMENT 'Row ID' ,
    label VARCHAR(64)    COMMENT 'Label' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    created_id VARCHAR(32)    COMMENT 'Created ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_id VARCHAR(32)    COMMENT 'Updated ID' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Tenant Option Items Translation';

CREATE TABLE tenant_config(
    id BIGINT(32) NOT NULL   COMMENT 'ID' ,
    tenant_id BIGINT(32)    COMMENT 'Tenant ID' ,
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
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'Tenant Config';

-- single immediately-prior name for a declared rename (the rename credential; excluded from
-- checksum/diff, written by the scanner/apply on rename). Model / Field / OptionSet / OptionItem only.
ALTER TABLE sys_model        ADD COLUMN renamed_from VARCHAR(64) COMMENT 'Renamed From';
ALTER TABLE sys_field        ADD COLUMN renamed_from VARCHAR(64) COMMENT 'Renamed From';
ALTER TABLE sys_option_set   ADD COLUMN renamed_from VARCHAR(64) COMMENT 'Renamed From';
ALTER TABLE sys_option_item  ADD COLUMN renamed_from VARCHAR(64) COMMENT 'Renamed From';
ALTER TABLE design_model       ADD COLUMN renamed_from VARCHAR(64) COMMENT 'Renamed From';
ALTER TABLE design_field       ADD COLUMN renamed_from VARCHAR(64) COMMENT 'Renamed From';
ALTER TABLE design_option_set  ADD COLUMN renamed_from VARCHAR(64) COMMENT 'Renamed From';
ALTER TABLE design_option_item ADD COLUMN renamed_from VARCHAR(64) COMMENT 'Renamed From';

CREATE TABLE sys_sequence(
    id BIGINT(32) NOT NULL AUTO_INCREMENT  COMMENT 'ID' ,
    tenant_id BIGINT(32)    COMMENT 'Tenant ID' ,
    code VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Sequence Code;Sequence code, e.g. "Employee.code"' ,
    template VARCHAR(64) NOT NULL  DEFAULT '' COMMENT 'Template;Format template, e.g. EMP-{yyyy}-{seq:5}' ,
    start_value BIGINT(32) NOT NULL   COMMENT 'Start Value;First number after each reset (default 1)' ,
    increment_step INT(11) NOT NULL   COMMENT 'Increment Step;Step size; v1 enforces 1' ,
    current_value BIGINT(32) NOT NULL   COMMENT 'Current Value;Last allocated value; next = current_value + step' ,
    reset_cadence VARCHAR(64) NOT NULL   COMMENT 'Reset Cadence' ,
    last_reset_key VARCHAR(64)    COMMENT 'Last Reset Key;Period key of the last reset, e.g. "2026" / "2026-04"' ,
    mode VARCHAR(64) NOT NULL   COMMENT 'Allocation Mode' ,
    description VARCHAR(256)    COMMENT 'Description' ,
    created_id BIGINT(32)    COMMENT 'Created ID' ,
    created_time DATETIME    COMMENT 'Created Time' ,
    created_by VARCHAR(64)    COMMENT 'Created By' ,
    updated_id BIGINT(32)    COMMENT 'Updated ID' ,
    updated_time DATETIME    COMMENT 'Updated Time' ,
    updated_by VARCHAR(64)    COMMENT 'Updated By' ,
    PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT = 'System Sequence';

ALTER TABLE sys_sequence ADD UNIQUE INDEX uk_sys_sequence_tenant_id_code (tenant_id,code);