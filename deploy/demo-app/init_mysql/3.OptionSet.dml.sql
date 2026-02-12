-- Option Set: FieldType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FieldType';
DELETE FROM sys_option_item WHERE option_set_code='FieldType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FieldType','Field Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','String','String',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','OneToOne','OneToOne',10,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','Time','Time',11,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','ManyToOne','ManyToOne',12,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','OneToMany','OneToMany',13,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','ManyToMany','ManyToMany',14,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','JSON','JSON',15,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','MultiString','MultiString',16,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','MultiOption','MultiOption',17,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','File','Single File',18,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','MultiFile','MultiFile',19,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','Integer','Integer',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','Filters','Filters',20,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','Orders','Orders',21,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','DTO','DTO',22,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','Long','Long',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','Double','Double',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','BigDecimal','BigDecimal',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','Option','Option Set',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','Boolean','Boolean',7,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','Date','Date',8,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FieldType','DateTime','DateTime',9,'','','');

-- Option Set: AccessType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='AccessType';
DELETE FROM sys_option_item WHERE option_set_code='AccessType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('AccessType','Access Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AccessType','Create','Create',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AccessType','Read','Read',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AccessType','Update','Update',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AccessType','Delete','Delete',4,'','','');

-- Option Set: WidgetType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='WidgetType';
DELETE FROM sys_option_item WHERE option_set_code='WidgetType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('WidgetType','Widget Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','URL','URL',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','CheckBox','Check Box',10,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','StatusBar','Status Bar',11,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','Image','Single Image',12,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','MultiImage','Multi Image',13,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','yyyy-MM','Year-Month Picker',14,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','MM-dd','Month-Day Picker',15,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','HH:mm','Hour-Minute Picker',16,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','HH:mm:ss','Time Picker',17,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','Email','Email',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','Html','HTML',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','Text','Text',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','Color','Color Picker',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','Monetary','Monetary',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','Percentage','Percentage',7,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','Slider','Slider',8,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('WidgetType','Radio','Radio Button',9,'','','');

-- Option Set: RoleUserFilter
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='RoleUserFilter';
DELETE FROM sys_option_item WHERE option_set_code='RoleUserFilter';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('RoleUserFilter','用户筛选字典','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleUserFilter','User','用户',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleUserFilter','Seq','专业序列',10,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleUserFilter','SeqSub','专业子序列',11,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleUserFilter','ManageStdPosition','管理标准岗',12,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleUserFilter','ManageSeq','管理序列',13,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleUserFilter','ManageSeqSub','管理子序列',14,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleUserFilter','Department','部门',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleUserFilter','Workplace','工作地点',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleUserFilter','PositionName','岗位名称',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleUserFilter','ReporterId','汇报人',7,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleUserFilter','Identity','任职身份',8,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleUserFilter','StdPosition','专业标准岗位',9,'','','');

-- Option Set: ViewType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ViewType';
DELETE FROM sys_option_item WHERE option_set_code='ViewType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ViewType','View Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ViewType','Table','Table',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ViewType','Form','Form',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ViewType','Card','Card',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ViewType','Kanban','Kanban',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ViewType','Calendar','Calendar',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ViewType','Dashboard','Dashboard',6,'','','');

-- Option Set: RoleDataFilter
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='RoleDataFilter';
DELETE FROM sys_option_item WHERE option_set_code='RoleDataFilter';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('RoleDataFilter','数据筛选字典','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataFilter','Dept','按部门',1,'','','');

-- Option Set: DeptLevel
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DeptLevel';
DELETE FROM sys_option_item WHERE option_set_code='DeptLevel';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DeptLevel','部门级别','销售部门级别，根据部门表的 type 和 deptRank 字段计算得出。示例 一级大区 type=05,deptRank=4; 二级大区 type=05,deptRank=5; 城市 type=05,deptRank=6。');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLevel','Full','全部部门',1,'','light,purple','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLevel','FirstLevelRegion','一级大区',2,'','light,red','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLevel','SecondLevelRegion','二级大区',3,'','light,yellow','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLevel','City','城市',4,'','light,green','');

-- Option Set: RoleDataValueFilter
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='RoleDataValueFilter';
DELETE FROM sys_option_item WHERE option_set_code='RoleDataValueFilter';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('RoleDataValueFilter','部门数据权限选项','按部门分配数据权限时选项');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataValueFilter','Full','全部部门',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataValueFilter','FirstLevelRegion','员工所在一级大区（含下级）',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataValueFilter','SecondLevelRegion','员工所在二级大区（含下级）',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataValueFilter','City','员工所在城市（含下级）',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataValueFilter','Own','员工所在部门（含下级）',7,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataValueFilter','Specific','指定部门（含下级）',8,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataValueFilter','LeaderDept','员工负责的部门（含下级）',9,'','','');

-- Option Set: ImportStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ImportStatus';
DELETE FROM sys_option_item WHERE option_set_code='ImportStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ImportStatus','Import Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ImportStatus','Processing','Processing',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ImportStatus','Success','Success',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ImportStatus','Failure','Failure',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ImportStatus','PartialFailure','Partial Failure',4,'','','');

-- Option Set: ImportRule
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ImportRule';
DELETE FROM sys_option_item WHERE option_set_code='ImportRule';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ImportRule','Import Rule','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ImportRule','OnlyUpdate','Only Update',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ImportRule','CreateOrUpdate','Create or Update',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ImportRule','OnlyCreate','Only Create',3,'','','');

-- Option Set: RoleDataFilterV2
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='RoleDataFilterV2';
DELETE FROM sys_option_item WHERE option_set_code='RoleDataFilterV2';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('RoleDataFilterV2','数据筛选字典 v2 版本','entity 能授权的属性');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataFilterV2','DeptId','部门',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataFilterV2','EmpId','员工',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataFilterV2','EmpDept','员工当前部门',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataFilterV2','FirstRegion','大区',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataFilterV2','City','城市',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataFilterV2','PerformanceItem','绩效项目',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('RoleDataFilterV2','State','状态',7,'','','');

-- Option Set: OptionItemColor
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='OptionItemColor';
DELETE FROM sys_option_item WHERE option_set_code='OptionItemColor';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('OptionItemColor','Option Item Color','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OptionItemColor','LightGrey','Light Grey',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OptionItemColor','DarkRed','Dark Red',10,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OptionItemColor','DarkBlue','Dark Blue',11,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OptionItemColor','DarkPurple','Dark Purple',12,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OptionItemColor','LightGreen','Light Green',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OptionItemColor','LightYellow','Light Yellow',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OptionItemColor','LightRed','Light Red',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OptionItemColor','LightBlue','Light Blue',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OptionItemColor','LightPurple','Light Purple',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OptionItemColor','DarkGrey','Dark Grey',7,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OptionItemColor','DarkGreen','Dark Green',8,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OptionItemColor','DarkYellow','Dark Yellow',9,'','','');

-- Option Set: MaskingType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='MaskingType';
DELETE FROM sys_option_item WHERE option_set_code='MaskingType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('MaskingType','Masking Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('MaskingType','All','Masks All Content',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('MaskingType','Name','Masks Name',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('MaskingType','Email','Masks Email',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('MaskingType','PhoneNumber','Masks Phone Number',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('MaskingType','IdNumber','Masks ID Number',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('MaskingType','CardNumber','Masks Card Number',6,'','','');

-- Option Set: FlowNotifyType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FlowNotifyType';
DELETE FROM sys_option_item WHERE option_set_code='FlowNotifyType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FlowNotifyType','Flow Notify Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNotifyType','TaskNotify','Task Notify',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNotifyType','ExceptionNotify','Exception Notify',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNotifyType','StatusChanged','Status Changed',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNotifyType','RemindNotify','Remind Notify',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNotifyType','MessageNotify','Message Notify',5,'','','');

-- Option Set: TriggerEventType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='TriggerEventType';
DELETE FROM sys_option_item WHERE option_set_code='TriggerEventType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('TriggerEventType','Trigger Event Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('TriggerEventType','CreateEvent','Create Event',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('TriggerEventType','SubflowEvent','Subflow Event',10,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('TriggerEventType','UpdateEvent','Update Event',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('TriggerEventType','CreateOrUpdate','Create or Update Event',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('TriggerEventType','DeleteEvent','Delete Event',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('TriggerEventType','ChangedEvent','Changed Event(C/U/D)',5,'','','Create/Update/Delete');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('TriggerEventType','ButtonEvent','Button Event',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('TriggerEventType','OnchangeEvent','Onchange Event',7,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('TriggerEventType','ApiEvent','API Event',8,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('TriggerEventType','CronEvent','Cron Event',9,'','','');

-- Option Set: FlowNodeType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FlowNodeType';
DELETE FROM sys_option_item WHERE option_set_code='FlowNodeType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FlowNodeType','Flow Node Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','ValidateData','Validate Data',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','TriggerSubflow','Trigger Subflow',10,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','ExtractTransform','Extract Transform',11,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','QueryAi','Query AI',12,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','GenerateReport','Generate Report',13,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','SendMessage','Send Message',14,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','WebHook','Web Hook',15,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','ReturnData','Return Data',16,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','LoopByDataset','Loop By Dataset',17,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','LoopByPage','Loop By Page',18,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','ApprovalNode','Approval Node',19,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','GetData','Get Data',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','TransferStage','Transfer Stage',20,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','ComputeData','Compute Data',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','UpdateData','Update Data',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','CreateData','Create Data',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','DeleteData','Delete Data',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','BranchGateway','Branch Gateway',7,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','Condition','Condition',8,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowNodeType','AsyncTask','Async Task',9,'','','');

-- Option Set: FlowLayoutType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FlowLayoutType';
DELETE FROM sys_option_item WHERE option_set_code='FlowLayoutType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FlowLayoutType','Flow Layout Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowLayoutType','VerticalAutomatic','Vertical Automatic',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowLayoutType','HorizontalAutomatic','Horizontal Automatic',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowLayoutType','ManualLayout','Manual Layout',3,'','','');

-- Option Set: FlowStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FlowStatus';
DELETE FROM sys_option_item WHERE option_set_code='FlowStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FlowStatus','Flow Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowStatus','Initial','Initial',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowStatus','Running','Running',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowStatus','Approving','Approving',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowStatus','Failed','Failed',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowStatus','Completed','Completed',5,'','','');

-- Option Set: FlowType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FlowType';
DELETE FROM sys_option_item WHERE option_set_code='FlowType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FlowType','Flow Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowType','AutomatedFlow','Automated Flow',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowType','FormFlow','Form Flow',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowType','ValidationFlow','Validation Flow',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowType','OnchangeFlow','Onchange Flow',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FlowType','AgentFlow','Agent Flow',5,'','','');

-- Option Set: ActionMessageType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ActionMessageType';
DELETE FROM sys_option_item WHERE option_set_code='ActionMessageType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ActionMessageType','Action Message Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionMessageType','SMS','SMS',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionMessageType','Email','Email',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionMessageType','InAppMessage','In-app Message',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionMessageType','InstantMessage','Instant Message',4,'','','');

-- Option Set: ActionGetDataType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ActionGetDataType';
DELETE FROM sys_option_item WHERE option_set_code='ActionGetDataType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ActionGetDataType','Action Get Data Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionGetDataType','MultiRows','Multi Rows',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionGetDataType','SingleRow','Single Row',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionGetDataType','OneFieldValue','One Field Value',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionGetDataType','OneFieldValues','One Field Values',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionGetDataType','Exist','Exist',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionGetDataType','Count','Count',6,'','','');

-- Option Set: PublishStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='PublishStatus';
DELETE FROM sys_option_item WHERE option_set_code='PublishStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('PublishStatus','Publish Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PublishStatus','Unreleased','Unreleased',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PublishStatus','InRelease','In Release',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PublishStatus','Released','Released',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PublishStatus','Failure','Failure',4,'','','');

-- Option Set: DatabaseType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DatabaseType';
DELETE FROM sys_option_item WHERE option_set_code='DatabaseType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DatabaseType','Database Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DatabaseType','MySQL','MySQL',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DatabaseType','PostgreSQL','PostgreSQL',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DatabaseType','TiDB','TiDB',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DatabaseType','ElasticSearch','ElasticSearch',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DatabaseType','MongoDB','MongoDB',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DatabaseType','Redis','Redis',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DatabaseType','Ignite','Ignite',7,'','','');

-- Option Set: AppEnvType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='AppEnvType';
DELETE FROM sys_option_item WHERE option_set_code='AppEnvType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('AppEnvType','App Env Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AppEnvType','Dev','Dev',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AppEnvType','Test','Test',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AppEnvType','UAT','UAT',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AppEnvType','Prod','Prod',4,'','','');

-- Option Set: IdStrategy
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='IdStrategy';
DELETE FROM sys_option_item WHERE option_set_code='IdStrategy';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('IdStrategy','ID Strategy','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('IdStrategy','DbAutoID','DB Auto-increment ID',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('IdStrategy','DistributedLong','Distributed Long ID',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('IdStrategy','DistributedString','Distributed String ID',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('IdStrategy','ExternalID','External ID',4,'','','');

-- Option Set: StorageType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='StorageType';
DELETE FROM sys_option_item WHERE option_set_code='StorageType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('StorageType','Storage Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('StorageType','RDBMS','RDBMS',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('StorageType','ES','ElasticSearch',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('StorageType','OLAP','OLAP Engine',3,'','','');

-- Option Set: BooleanValue
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='BooleanValue';
DELETE FROM sys_option_item WHERE option_set_code='BooleanValue';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('BooleanValue','Boolean Option','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('BooleanValue','true','Yes',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('BooleanValue','false','No',2,'','','');

-- Option Set: ActionExceptionSignal
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ActionExceptionSignal';
DELETE FROM sys_option_item WHERE option_set_code='ActionExceptionSignal';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ActionExceptionSignal','Action Exception Signal','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionExceptionSignal','SkipNodeActions','Skip Current Node Actions',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionExceptionSignal','EndFlow','End Current Flow',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionExceptionSignal','EndLoopNode','End Current Loop Node',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionExceptionSignal','ThrowException','Throw Exception',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionExceptionSignal','LogError','Log Error',5,'','','');

-- Option Set: ActionExceptionType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ActionExceptionType';
DELETE FROM sys_option_item WHERE option_set_code='ActionExceptionType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ActionExceptionType','Action Exception Type','Check the exception condition and throw an exception.');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionExceptionType','ResultIsEmpty','Result Is Empty',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionExceptionType','ResultIsEmptyOrZero','Result Is Empty Or Zero',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionExceptionType','ResultIsNotEmpty','Result Is Not Empty',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionExceptionType','ResultIsFalse','Result Is False',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('ActionExceptionType','ResultIsTrue','Result Is True',5,'','','');

-- Option Set: CronStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='CronStatus';
DELETE FROM sys_option_item WHERE option_set_code='CronStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('CronStatus','Cron Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('CronStatus','Scheduled','Scheduled',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('CronStatus','Running','Running',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('CronStatus','Completed','Completed',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('CronStatus','Paused','Paused',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('CronStatus','Cancelled','Cancelled',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('CronStatus','Skipped','Skipped',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('CronStatus','Timeout','Timeout',7,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('CronStatus','Failed','Failed',8,'','','');

-- Option Set: MessageResult
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='MessageResult';
DELETE FROM sys_option_item WHERE option_set_code='MessageResult';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('MessageResult','消息发送结果','结果（Result）');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('MessageResult','Succeed','成功',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('MessageResult','Failed','失败',2,'','','');

-- Option Set: DeptLabel
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DeptLabel';
DELETE FROM sys_option_item WHERE option_set_code='DeptLabel';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DeptLabel','部门标签','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLabel','BigKA','大KA',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLabel','SmallKA','小KA',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLabel','SME','SME',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLabel','NewTraining','新训营',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLabel','Operations','运营',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLabel','HR','HR',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLabel','Training','培训',7,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLabel','Market','市场',8,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLabel','AdministrationOrIT','行政/IT',9,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptLabel','Other','其他',10,'','','');

-- Option Set: DeptStationLevel
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DeptStationLevel';
DELETE FROM sys_option_item WHERE option_set_code='DeptStationLevel';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DeptStationLevel','部门站级','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptStationLevel','FirstTier','一级站',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptStationLevel','NoFirstTier','二级站',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptStationLevel','SecondaryCenter','二级中心站',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptStationLevel','NewSite','三级站',4,'','','');

-- Option Set: DeptState
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DeptState';
DELETE FROM sys_option_item WHERE option_set_code='DeptState';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DeptState','部门状态','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptState','Active','启用',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptState','Repeal','撤销',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptState','Disable','已禁用',3,'','','');

-- Option Set: DeptType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DeptType';
DELETE FROM sys_option_item WHERE option_set_code='DeptType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DeptType','部门类型','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptType','1','大区级',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptType','2','城市级',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptType','3','业务组织',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptType','4','职能组织',4,'','','');

-- Option Set: DeptRank
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DeptRank';
DELETE FROM sys_option_item WHERE option_set_code='DeptRank';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DeptRank','部门级别','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptRank','1','一级部门',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptRank','2','二级部门',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptRank','3','三级部门',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptRank','4','四级部门',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptRank','5','五级部门',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptRank','6','六级部门',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptRank','7','七级部门',7,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DeptRank','8','八级部门',8,'','','');

-- Option Set: DataType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DataType';
DELETE FROM sys_option_item WHERE option_set_code='DataType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DataType','Data Type','字段值的Java数据类型');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DataType','String','String',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DataType','MultiString','MultiString',10,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DataType','Integer','Integer',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DataType','Long','Long',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DataType','Double','Double',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DataType','BigDecimal','BigDecimal',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DataType','Boolean','Boolean',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DataType','Date','Date',7,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DataType','Datetime','Datetime',8,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('DataType','JSON','JSON',9,'','','');

-- Option Set: FileType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FileType';
DELETE FROM sys_option_item WHERE option_set_code='FileType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FileType','File Type','');
-- Insert option set items

-- Option Set: FileSource
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FileSource';
DELETE FROM sys_option_item WHERE option_set_code='FileSource';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FileSource','File Source','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FileSource','Download','Download',0,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('FileSource','Upload','Upload',0,'','','');

-- Option Set: Language
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='Language';
DELETE FROM sys_option_item WHERE option_set_code='Language';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('Language','','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Language','en-US','English (US)',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Language','zh-CN','Chinese (Simplified) / 简体中文',2,'','','');

-- Option Set: AiModelType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='AiModelType';
DELETE FROM sys_option_item WHERE option_set_code='AiModelType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('AiModelType','AI Model Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiModelType','GPT','GPT',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiModelType','Image','Image Model',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiModelType','Audio','Audio Model',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiModelType','Video','Video Model',4,'','','');

-- Option Set: AiModelProvider
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='AiModelProvider';
DELETE FROM sys_option_item WHERE option_set_code='AiModelProvider';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('AiModelProvider','AI Model Provider','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiModelProvider','OpenAI','OpenAI',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiModelProvider','AzureOpenAI','Azure OpenAI',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiModelProvider','ClaudeAI','Claude AI',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiModelProvider','ChatGLM','ChatGLM',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiModelProvider','DeepSeek','DeepSeek',0,'','','');

-- Option Set: AiMessageRole
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='AiMessageRole';
DELETE FROM sys_option_item WHERE option_set_code='AiMessageRole';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('AiMessageRole','AI Message Role','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiMessageRole','User','User',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiMessageRole','Assistant','Assistant',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiMessageRole','System','System',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiMessageRole','Tool','Tool',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiMessageRole','Function','Function',5,'','','');

-- Option Set: AiMessageStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='AiMessageStatus';
DELETE FROM sys_option_item WHERE option_set_code='AiMessageStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('AiMessageStatus','AI Message Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiMessageStatus','Pending','Pending',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiMessageStatus','Completed','Completed',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiMessageStatus','Interrupted','Interrupted',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AiMessageStatus','Failed','Failed',4,'','','');

-- Option Set: OrderStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='OrderStatus';
DELETE FROM sys_option_item WHERE option_set_code='OrderStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('OrderStatus','订单状态','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OrderStatus','PendingPayment','待付款',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OrderStatus','InProgress','进行中',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OrderStatus','Completed','已完成',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OrderStatus','Canceled','已取消',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OrderStatus','Refunded','已退款',5,'','','');

-- Option Set: PaymentMethod
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='PaymentMethod';
DELETE FROM sys_option_item WHERE option_set_code='PaymentMethod';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('PaymentMethod','支付方式','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PaymentMethod','WeChat','微信支付',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PaymentMethod','AliPay','支付宝',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PaymentMethod','Stripe','Stripe',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PaymentMethod','PayPal','PayPal',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PaymentMethod','ApplePay','Apple Pay',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PaymentMethod','OfflinePayment','线下支付',6,'','','');

-- Option Set: PaymentStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='PaymentStatus';
DELETE FROM sys_option_item WHERE option_set_code='PaymentStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('PaymentStatus','支付状态','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PaymentStatus','Unpaid','待支付',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PaymentStatus','Paid','已支付',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PaymentStatus','Failed','支付失败',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PaymentStatus','Canceled','取消支付',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('PaymentStatus','Refunded','已退款',5,'','','');

-- Option Set: LoginMethod
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='LoginMethod';
DELETE FROM sys_option_item WHERE option_set_code='LoginMethod';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('LoginMethod','Login Method','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginMethod','Password','Password',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginMethod','SmsCode','SMS Code',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginMethod','EmailCode','Email Code',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginMethod','SSO','SSO',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginMethod','Apple','Apple',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginMethod','WeChat','WeChat',7,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginMethod','Gmail','Gmail',8,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginMethod','Facebook','Facebook',9,'','','');

-- Option Set: AccountStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='AccountStatus';
DELETE FROM sys_option_item WHERE option_set_code='AccountStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('AccountStatus','Account Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AccountStatus','Active','Active',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AccountStatus','Unverified','Unverified',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AccountStatus','Frozen','Frozen',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AccountStatus','PendingDeletion','Pending Deletion',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AccountStatus','Deleted','Deleted',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('AccountStatus','Blacklisted','Blacklisted',6,'','','');

-- Option Set: OAuthProvider
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='OAuthProvider';
DELETE FROM sys_option_item WHERE option_set_code='OAuthProvider';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('OAuthProvider','OAuth Provider','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OAuthProvider','Apple','Apple',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OAuthProvider','Google','Google',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OAuthProvider','TikTok','TikTok',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OAuthProvider','X','X',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('OAuthProvider','LinkedIn','LinkedIn',5,'','','');

-- Option Set: LoginDeviceType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='LoginDeviceType';
DELETE FROM sys_option_item WHERE option_set_code='LoginDeviceType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('LoginDeviceType','Login Device Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginDeviceType','WebBrowser','Web Browser',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginDeviceType','MobileApp','Mobile App',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginDeviceType','DesktopAPP','Desktop APP',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginDeviceType','MiniProgram','Mini Program',4,'','','');

-- Option Set: Gender
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='Gender';
DELETE FROM sys_option_item WHERE option_set_code='Gender';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('Gender','Gender','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Gender','Male','Male',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Gender','Female','Female',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Gender','Other','Other',3,'','','');

-- Option Set: Timezone
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='Timezone';
DELETE FROM sys_option_item WHERE option_set_code='Timezone';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('Timezone','Timezone','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC-12:00','UTC-12:00 (Baker Island)',1,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC-03:30','UTC-03:30 (Newfoundland)',10,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC-03:00','UTC-03:00 (Buenos Aires)',11,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC-02:00','UTC-02:00 (South Georgia)',12,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC-01:00','UTC-01:00 (Azores)',13,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+00:00','UTC+00:00 (London, GMT)',14,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+01:00','UTC+01:00 (Paris, CET)',15,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+02:00','UTC+02:00 (Athens, EET)',16,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+03:00','UTC+03:00 (Moscow, MSK)',17,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+03:30','UTC+03:30 (Tehran)',18,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+04:00','UTC+04:00 (Dubai, GST)',19,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC-11:00','UTC-11:00 (Samoa)',2,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+04:30','UTC+04:30 (Kabul)',20,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+05:00','UTC+05:00 (Pakistan)',21,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+05:30','UTC+05:30 (India, IST)',22,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+05:45','UTC+05:45 (Nepal)',23,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+06:00','UTC+06:00 (Bangladesh)',24,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+06:30','UTC+06:30 (Myanmar)',25,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+07:00','UTC+07:00 (Bangkok, ICT)',26,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+08:00','UTC+08:00 (Beijing, CST)',27,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+09:00','UTC+09:00 (Tokyo, JST)',28,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+09:30','UTC+09:30 (Adelaide, ACST)',29,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC-10:00','UTC-10:00 (Hawaii)',3,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+10:00','UTC+10:00 (Sydney, AEST)',30,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+11:00','UTC+11:00 (Solomon Islands)',31,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+12:00','UTC+12:00 (Auckland, NZST)',32,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+13:00','UTC+13:00 (Tonga)',33,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC+14:00','UTC+14:00 (Kiritimati)',34,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC-09:00','UTC-09:00 (Alaska)',4,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC-08:00','UTC-08:00 (Los Angeles, PST)',5,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC-07:00','UTC-07:00 (Denver, MST)',6,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC-06:00','UTC-06:00 (Chicago, CST)',7,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC-05:00','UTC-05:00 (New York, EST)',8,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('Timezone','UTC-04:00','UTC-04:00 (Halifax, AST)',9,'','','');

-- Option Set: LoginStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='LoginStatus';
DELETE FROM sys_option_item WHERE option_set_code='LoginStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('LoginStatus','Login Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginStatus','Success','Success',0,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginStatus','Invalid','Invalid',0,'','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_color,description) VALUES('LoginStatus','NotFound','Not Found',0,'','','');

