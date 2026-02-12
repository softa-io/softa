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

