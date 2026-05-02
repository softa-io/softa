-- Option Set: FieldType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FieldType';
DELETE FROM sys_option_item WHERE option_set_code='FieldType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FieldType','Field Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','String','String',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','OneToOne','OneToOne',10,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','Time','Time',11,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','ManyToOne','ManyToOne',12,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','OneToMany','OneToMany',13,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','ManyToMany','ManyToMany',14,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','JSON','JSON',15,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','MultiString','MultiString',16,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','MultiOption','MultiOption',17,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','File','Single File',18,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','MultiFile','MultiFile',19,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','Integer','Integer',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','Filters','Filters',20,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','Orders','Orders',21,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','DTO','DTO',22,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','Long','Long',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','Double','Double',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','BigDecimal','BigDecimal',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','Option','Option Set',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','Boolean','Boolean',7,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','Date','Date',8,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FieldType','DateTime','DateTime',9,'','','','');

-- Option Set: AccessType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='AccessType';
DELETE FROM sys_option_item WHERE option_set_code='AccessType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('AccessType','Access Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AccessType','Create','Create',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AccessType','Read','Read',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AccessType','Update','Update',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AccessType','Delete','Delete',4,'','','','');

-- Option Set: ViewType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ViewType';
DELETE FROM sys_option_item WHERE option_set_code='ViewType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ViewType','View Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ViewType','Table','Table',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ViewType','Form','Form',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ViewType','Card','Card',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ViewType','Kanban','Kanban',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ViewType','Calendar','Calendar',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ViewType','Dashboard','Dashboard',6,'','','','');

-- Option Set: WidgetType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='WidgetType';
DELETE FROM sys_option_item WHERE option_set_code='WidgetType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('WidgetType','Widget Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','URL','URL',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','CheckBox','Check Box',10,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','JsonTree','JSON Tree',11,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','StatusBar','Status Bar',11,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','SelectTree','Select Tree',11,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','TagList','Tag List',11,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Image','Single Image',12,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','MultiImage','Multi Image',13,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','yyyy-MM','Year-Month Picker',14,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','MM-dd','Month-Day Picker',15,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','HH:mm','Hour-Minute Picker',16,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','HH:mm:ss','Time Picker',17,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Email','Email',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Text','Text',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','RichText','Rich Text',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Code','Code',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Markdown','Markdown',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Color','Color Picker',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Monetary','Monetary',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Percentage','Percentage',7,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Slider','Slider',8,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Radio','Radio Button',9,'','','','');

-- Option Set: ImportStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ImportStatus';
DELETE FROM sys_option_item WHERE option_set_code='ImportStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ImportStatus','Import Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ImportStatus','Processing','Processing',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ImportStatus','Success','Success',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ImportStatus','Failure','Failure',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ImportStatus','PartialFailure','Partial Failure',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ImportStatus','ValidationSuccess','Validation Success',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ImportStatus','ValidationFailure','Validation Failure',6,'','','','');

-- Option Set: ImportType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ImportType';
DELETE FROM sys_option_item WHERE option_set_code='ImportType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ImportType','Import Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ImportType','Import','Import',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ImportType','Validate','Validate',2,'','','','');

-- Option Set: ImportRule
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ImportRule';
DELETE FROM sys_option_item WHERE option_set_code='ImportRule';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ImportRule','Import Rule','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ImportRule','OnlyUpdate','Only Update',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ImportRule','CreateOrUpdate','Create or Update',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ImportRule','OnlyCreate','Only Create',3,'','','','');

-- Option Set: OptionItemColor (removed; cleanup retained for one release)
DELETE FROM sys_option_set WHERE option_set_code='OptionItemColor';
DELETE FROM sys_option_item WHERE option_set_code='OptionItemColor';

-- Option Set: OptionItemTone
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='OptionItemTone';
DELETE FROM sys_option_item WHERE option_set_code='OptionItemTone';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('OptionItemTone','Option Item Tone','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemTone','success','Success',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemTone','warning','Warning',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemTone','error','Error',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemTone','info','Info',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemTone','neutral','Neutral',5,'','','','');

-- Option Set: OptionItemIcon
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='OptionItemIcon';
DELETE FROM sys_option_item WHERE option_set_code='OptionItemIcon';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('OptionItemIcon','Option Item Icon','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemIcon','check','Check',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemIcon','x','X',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemIcon','ban','Ban',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemIcon','alert','Alert',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemIcon','pause','Pause',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemIcon','info','Info',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemIcon','eye','Eye',7,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemIcon','loader','Loader',8,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemIcon','clock','Clock',9,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemIcon','pending','Pending',10,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemIcon','undo','Undo',11,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OptionItemIcon','lock','Lock',12,'','','','');

-- Option Set: MaskingType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='MaskingType';
DELETE FROM sys_option_item WHERE option_set_code='MaskingType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('MaskingType','Masking Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('MaskingType','All','Masks All Content',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('MaskingType','Name','Masks Name',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('MaskingType','Email','Masks Email',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('MaskingType','PhoneNumber','Masks Phone Number',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('MaskingType','IdNumber','Masks ID Number',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('MaskingType','CardNumber','Masks Card Number',6,'','','','');

-- Option Set: FlowNotifyType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FlowNotifyType';
DELETE FROM sys_option_item WHERE option_set_code='FlowNotifyType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FlowNotifyType','Flow Notify Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNotifyType','TaskNotify','Task Notify',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNotifyType','ExceptionNotify','Exception Notify',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNotifyType','StatusChanged','Status Changed',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNotifyType','RemindNotify','Remind Notify',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNotifyType','MessageNotify','Message Notify',5,'','','','');

-- Option Set: TriggerEventType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='TriggerEventType';
DELETE FROM sys_option_item WHERE option_set_code='TriggerEventType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('TriggerEventType','Trigger Event Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','CreateEvent','Create Event',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','SubflowEvent','Subflow Event',10,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','UpdateEvent','Update Event',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','CreateOrUpdate','Create or Update Event',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','DeleteEvent','Delete Event',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','ChangedEvent','Changed Event(C/U/D)',5,'','','','Create/Update/Delete');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','ButtonEvent','Button Event',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','OnchangeEvent','Onchange Event',7,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','ApiEvent','API Event',8,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','CronEvent','Cron Event',9,'','','','');

-- Option Set: FlowNodeType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FlowNodeType';
DELETE FROM sys_option_item WHERE option_set_code='FlowNodeType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FlowNodeType','Flow Node Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','ValidateData','Validate Data',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','TriggerSubflow','Trigger Subflow',10,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','ExtractTransform','Extract Transform',11,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','QueryAi','Query AI',12,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','GenerateReport','Generate Report',13,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','SendMessage','Send Message',14,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','WebHook','Web Hook',15,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','ReturnData','Return Data',16,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','LoopByDataset','Loop By Dataset',17,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','LoopByPage','Loop By Page',18,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','ApprovalNode','Approval Node',19,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','GetData','Get Data',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','TransferStage','Transfer Stage',20,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','ComputeData','Compute Data',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','UpdateData','Update Data',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','CreateData','Create Data',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','DeleteData','Delete Data',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','BranchGateway','Branch Gateway',7,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','Condition','Condition',8,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNodeType','AsyncTask','Async Task',9,'','','','');

-- Option Set: FlowLayoutType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FlowLayoutType';
DELETE FROM sys_option_item WHERE option_set_code='FlowLayoutType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FlowLayoutType','Flow Layout Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowLayoutType','VerticalAutomatic','Vertical Automatic',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowLayoutType','HorizontalAutomatic','Horizontal Automatic',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowLayoutType','ManualLayout','Manual Layout',3,'','','','');

-- Option Set: FlowStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FlowStatus';
DELETE FROM sys_option_item WHERE option_set_code='FlowStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FlowStatus','Flow Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowStatus','Initial','Initial',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowStatus','Running','Running',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowStatus','Approving','Approving',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowStatus','Failed','Failed',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowStatus','Completed','Completed',5,'','','','');

-- Option Set: FlowType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='FlowType';
DELETE FROM sys_option_item WHERE option_set_code='FlowType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('FlowType','Flow Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowType','AutomatedFlow','Automated Flow',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowType','FormFlow','Form Flow',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowType','ValidationFlow','Validation Flow',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowType','OnchangeFlow','Onchange Flow',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowType','AgentFlow','Agent Flow',5,'','','','');

-- Option Set: ActionMessageType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ActionMessageType';
DELETE FROM sys_option_item WHERE option_set_code='ActionMessageType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ActionMessageType','Action Message Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionMessageType','SMS','SMS',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionMessageType','Email','Email',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionMessageType','InAppMessage','In-app Message',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionMessageType','InstantMessage','Instant Message',4,'','','','');

-- Option Set: ActionGetDataType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ActionGetDataType';
DELETE FROM sys_option_item WHERE option_set_code='ActionGetDataType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ActionGetDataType','Action Get Data Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionGetDataType','MultiRows','Multi Rows',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionGetDataType','SingleRow','Single Row',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionGetDataType','OneFieldValue','One Field Value',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionGetDataType','OneFieldValues','One Field Values',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionGetDataType','Exist','Exist',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionGetDataType','Count','Count',6,'','','','');

-- Option Set: DesignDeploymentStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DesignDeploymentStatus';
DELETE FROM sys_option_item WHERE option_set_code='DesignDeploymentStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DesignDeploymentStatus','Design Release Deployment Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignDeploymentStatus','Pending','Pending',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignDeploymentStatus','Deploying','Deploying',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignDeploymentStatus','Success','Success',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignDeploymentStatus','Failure','Failure',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignDeploymentStatus','RolledBack','Rolled Back',5,'','','','');

-- Option Set: DesignCodeLang
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DesignCodeLang';
DELETE FROM sys_option_item WHERE option_set_code='DesignCodeLang';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DesignCodeLang','Design Code Lang','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignCodeLang','Java','Java',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignCodeLang','Rust','Rust',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignCodeLang','Golang','Golang',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignCodeLang','TypeScript','TypeScript',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignCodeLang','Python','Python',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignCodeLang','Csharp','Csharp',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignCodeLang','Ruby','Ruby',8,'','','','');

-- Option Set: DesignAppStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DesignAppStatus';
DELETE FROM sys_option_item WHERE option_set_code='DesignAppStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DesignAppStatus','Design App Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppStatus','Active','Active',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppStatus','Maintenance','Maintenance',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppStatus','Deprecated','Deprecated',3,'','','','');

-- Option Set: DesignPortfolioStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DesignPortfolioStatus';
DELETE FROM sys_option_item WHERE option_set_code='DesignPortfolioStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DesignPortfolioStatus','Design Portfolio Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignPortfolioStatus','Active','Active',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignPortfolioStatus','Archived','Archived',2,'','','','');

-- Option Set: DesignWorkItemStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DesignWorkItemStatus';
DELETE FROM sys_option_item WHERE option_set_code='DesignWorkItemStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DesignWorkItemStatus','Design Work Item Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignWorkItemStatus','InProgress','In Progress',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignWorkItemStatus','Done','Done',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignWorkItemStatus','Deferred','Deferred',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignWorkItemStatus','Closed','Closed',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignWorkItemStatus','Cancelled','Cancelled',5,'','','','');

-- Option Set: DesignAppEnvType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DesignAppEnvType';
DELETE FROM sys_option_item WHERE option_set_code='DesignAppEnvType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DesignAppEnvType','Design App Env Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppEnvType','Dev','Dev',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppEnvType','Test','Test',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppEnvType','UAT','UAT',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppEnvType','Prod','Prod',4,'','','','');

-- Option Set: DesignAppEnvStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DesignAppEnvStatus';
DELETE FROM sys_option_item WHERE option_set_code='DesignAppEnvStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DesignAppEnvStatus','Design App Env Status','Per-env deployment mutex status');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppEnvStatus','Stable','Stable',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppEnvStatus','Deploying','Deploying',2,'','','','');

-- Option Set: DesignDriftCheckStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DesignDriftCheckStatus';
DELETE FROM sys_option_item WHERE option_set_code='DesignDriftCheckStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DesignDriftCheckStatus','Design Drift Check Status','Outcome of the last drift check');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignDriftCheckStatus','Success','Success',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignDriftCheckStatus','Failure','Failure',2,'','','','');

-- Option Set: DesignAppVersionStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DesignAppVersionStatus';
DELETE FROM sys_option_item WHERE option_set_code='DesignAppVersionStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DesignAppVersionStatus','Design App Version Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppVersionStatus','Draft','Draft',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppVersionStatus','Sealed','Sealed',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppVersionStatus','Frozen','Frozen',3,'','','','');

-- Option Set: DesignAppVersionType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DesignAppVersionType';
DELETE FROM sys_option_item WHERE option_set_code='DesignAppVersionType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DesignAppVersionType','Design App Version Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppVersionType','Normal','Normal',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DesignAppVersionType','Hotfix','Hotfix',2,'','','','');

-- Option Set: IdStrategy
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='IdStrategy';
DELETE FROM sys_option_item WHERE option_set_code='IdStrategy';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('IdStrategy','ID Strategy','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('IdStrategy','DbAutoID','DB Auto-increment ID',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('IdStrategy','DistributedLong','Distributed Long ID',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('IdStrategy','DistributedString','Distributed String ID',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('IdStrategy','ExternalID','External ID',4,'','','','');

-- Option Set: StorageType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='StorageType';
DELETE FROM sys_option_item WHERE option_set_code='StorageType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('StorageType','Storage Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('StorageType','RDBMS','RDBMS',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('StorageType','ES','ElasticSearch',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('StorageType','OLAP','OLAP Engine',3,'','','','');

-- Option Set: BooleanValue
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='BooleanValue';
DELETE FROM sys_option_item WHERE option_set_code='BooleanValue';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('BooleanValue','Boolean Option','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('BooleanValue','true','Yes',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('BooleanValue','false','No',2,'','','','');

-- Option Set: ActionExceptionSignal
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ActionExceptionSignal';
DELETE FROM sys_option_item WHERE option_set_code='ActionExceptionSignal';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ActionExceptionSignal','Action Exception Signal','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionSignal','SkipNodeActions','Skip Current Node Actions',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionSignal','EndFlow','End Current Flow',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionSignal','EndLoopNode','End Current Loop Node',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionSignal','ThrowException','Throw Exception',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionSignal','LogError','Log Error',5,'','','','');

-- Option Set: ActionExceptionType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='ActionExceptionType';
DELETE FROM sys_option_item WHERE option_set_code='ActionExceptionType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('ActionExceptionType','Action Exception Type','Check the exception condition and throw an exception.');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionType','ResultIsEmpty','Result Is Empty',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionType','ResultIsEmptyOrZero','Result Is Empty Or Zero',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionType','ResultIsNotEmpty','Result Is Not Empty',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionType','ResultIsFalse','Result Is False',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionType','ResultIsTrue','Result Is True',5,'','','','');

-- Option Set: CronStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='CronStatus';
DELETE FROM sys_option_item WHERE option_set_code='CronStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('CronStatus','Cron Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('CronStatus','Scheduled','Scheduled',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('CronStatus','Running','Running',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('CronStatus','Completed','Completed',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('CronStatus','Paused','Paused',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('CronStatus','Cancelled','Cancelled',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('CronStatus','Skipped','Skipped',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('CronStatus','Timeout','Timeout',7,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('CronStatus','Failed','Failed',8,'','','','');

-- Option Set: DataType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DataType';
DELETE FROM sys_option_item WHERE option_set_code='DataType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DataType','Data Type','Java data type of the field value');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','String','String',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','MultiString','MultiString',10,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Integer','Integer',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Long','Long',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Double','Double',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','BigDecimal','BigDecimal',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Boolean','Boolean',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Date','Date',7,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Datetime','Datetime',8,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','JSON','JSON',9,'','','','');

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
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FileSource','Download','Download',0,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FileSource','Upload','Upload',0,'','','','');

-- Option Set: Language
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='Language';
DELETE FROM sys_option_item WHERE option_set_code='Language';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('Language','Language','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Language','en-US','English (US)',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Language','zh-CN','Chinese (Simplified) / 简体中文',2,'','','','');

-- Option Set: AiModelType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='AiModelType';
DELETE FROM sys_option_item WHERE option_set_code='AiModelType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('AiModelType','AI Model Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiModelType','GPT','GPT',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiModelType','Image','Image Model',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiModelType','Audio','Audio Model',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiModelType','Video','Video Model',4,'','','','');

-- Option Set: AiModelProvider
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='AiModelProvider';
DELETE FROM sys_option_item WHERE option_set_code='AiModelProvider';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('AiModelProvider','AI Model Provider','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiModelProvider','OpenAI','OpenAI',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiModelProvider','AzureOpenAI','Azure OpenAI',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiModelProvider','ClaudeAI','Claude AI',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiModelProvider','ChatGLM','ChatGLM',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiModelProvider','DeepSeek','DeepSeek',0,'','','','');

-- Option Set: AiMessageRole
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='AiMessageRole';
DELETE FROM sys_option_item WHERE option_set_code='AiMessageRole';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('AiMessageRole','AI Message Role','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiMessageRole','User','User',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiMessageRole','Assistant','Assistant',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiMessageRole','System','System',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiMessageRole','Tool','Tool',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiMessageRole','Function','Function',5,'','','','');

-- Option Set: AiMessageStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='AiMessageStatus';
DELETE FROM sys_option_item WHERE option_set_code='AiMessageStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('AiMessageStatus','AI Message Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiMessageStatus','Pending','Pending',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiMessageStatus','Completed','Completed',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiMessageStatus','Interrupted','Interrupted',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AiMessageStatus','Failed','Failed',4,'','','','');

-- Option Set: OrderStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='OrderStatus';
DELETE FROM sys_option_item WHERE option_set_code='OrderStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('OrderStatus','Order Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OrderStatus','PendingPayment','Pending Payment',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OrderStatus','InProgress','In Progress',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OrderStatus','Completed','Completed',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OrderStatus','Canceled','Canceled',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OrderStatus','Refunded','Refunded',5,'','','','');

-- Option Set: PaymentMethod
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='PaymentMethod';
DELETE FROM sys_option_item WHERE option_set_code='PaymentMethod';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('PaymentMethod','Payment Method','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('PaymentMethod','WeChat','WeChat Pay',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('PaymentMethod','AliPay','Alipay',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('PaymentMethod','Stripe','Stripe',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('PaymentMethod','PayPal','PayPal',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('PaymentMethod','ApplePay','Apple Pay',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('PaymentMethod','OfflinePayment','Offline Payment',6,'','','','');

-- Option Set: PaymentStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='PaymentStatus';
DELETE FROM sys_option_item WHERE option_set_code='PaymentStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('PaymentStatus','Payment Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('PaymentStatus','Unpaid','Unpaid',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('PaymentStatus','Paid','Paid',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('PaymentStatus','Failed','Payment Failed',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('PaymentStatus','Canceled','Payment Canceled',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('PaymentStatus','Refunded','Refunded',5,'','','','');

-- Option Set: LoginMethod
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='LoginMethod';
DELETE FROM sys_option_item WHERE option_set_code='LoginMethod';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('LoginMethod','Login Method','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginMethod','Password','Password',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginMethod','SmsCode','SMS Code',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginMethod','EmailCode','Email Code',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginMethod','SSO','SSO',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginMethod','Apple','Apple',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginMethod','WeChat','WeChat',7,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginMethod','Gmail','Gmail',8,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginMethod','Facebook','Facebook',9,'','','','');

-- Option Set: AccountStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='AccountStatus';
DELETE FROM sys_option_item WHERE option_set_code='AccountStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('AccountStatus','Account Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AccountStatus','Active','Active',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AccountStatus','Unverified','Unverified',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AccountStatus','Frozen','Frozen',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AccountStatus','PendingDeletion','Pending Deletion',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AccountStatus','Deleted','Deleted',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('AccountStatus','Blacklisted','Blacklisted',6,'','','','');

-- Option Set: OAuthProvider
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='OAuthProvider';
DELETE FROM sys_option_item WHERE option_set_code='OAuthProvider';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('OAuthProvider','OAuth Provider','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OAuthProvider','Apple','Apple',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OAuthProvider','Google','Google',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OAuthProvider','TikTok','TikTok',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OAuthProvider','X','X',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('OAuthProvider','LinkedIn','LinkedIn',5,'','','','');

-- Option Set: LoginDeviceType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='LoginDeviceType';
DELETE FROM sys_option_item WHERE option_set_code='LoginDeviceType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('LoginDeviceType','Login Device Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginDeviceType','WebBrowser','Web Browser',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginDeviceType','MobileApp','Mobile App',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginDeviceType','DesktopAPP','Desktop APP',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginDeviceType','MiniProgram','Mini Program',4,'','','','');

-- Option Set: Gender
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='Gender';
DELETE FROM sys_option_item WHERE option_set_code='Gender';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('Gender','Gender','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Gender','Male','Male',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Gender','Female','Female',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Gender','Other','Other',3,'','','','');

-- Option Set: Timezone
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='Timezone';
DELETE FROM sys_option_item WHERE option_set_code='Timezone';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('Timezone','Timezone','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC-12:00','UTC-12:00 (Baker Island)',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC-03:30','UTC-03:30 (Newfoundland)',10,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC-03:00','UTC-03:00 (Buenos Aires)',11,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC-02:00','UTC-02:00 (South Georgia)',12,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC-01:00','UTC-01:00 (Azores)',13,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+00:00','UTC+00:00 (London, GMT)',14,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+01:00','UTC+01:00 (Paris, CET)',15,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+02:00','UTC+02:00 (Athens, EET)',16,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+03:00','UTC+03:00 (Moscow, MSK)',17,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+03:30','UTC+03:30 (Tehran)',18,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+04:00','UTC+04:00 (Dubai, GST)',19,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC-11:00','UTC-11:00 (Samoa)',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+04:30','UTC+04:30 (Kabul)',20,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+05:00','UTC+05:00 (Pakistan)',21,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+05:30','UTC+05:30 (India, IST)',22,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+05:45','UTC+05:45 (Nepal)',23,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+06:00','UTC+06:00 (Bangladesh)',24,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+06:30','UTC+06:30 (Myanmar)',25,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+07:00','UTC+07:00 (Bangkok, ICT)',26,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+08:00','UTC+08:00 (Beijing, CST)',27,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+09:00','UTC+09:00 (Tokyo, JST)',28,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+09:30','UTC+09:30 (Adelaide, ACST)',29,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC-10:00','UTC-10:00 (Hawaii)',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+10:00','UTC+10:00 (Sydney, AEST)',30,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+11:00','UTC+11:00 (Solomon Islands)',31,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+12:00','UTC+12:00 (Auckland, NZST)',32,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+13:00','UTC+13:00 (Tonga)',33,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC+14:00','UTC+14:00 (Kiritimati)',34,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC-09:00','UTC-09:00 (Alaska)',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC-08:00','UTC-08:00 (Los Angeles, PST)',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC-07:00','UTC-07:00 (Denver, MST)',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC-06:00','UTC-06:00 (Chicago, CST)',7,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC-05:00','UTC-05:00 (New York, EST)',8,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Timezone','UTC-04:00','UTC-04:00 (Halifax, AST)',9,'','','','');

-- Option Set: LoginStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='LoginStatus';
DELETE FROM sys_option_item WHERE option_set_code='LoginStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('LoginStatus','Login Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginStatus','Success','Success',0,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginStatus','Invalid','Invalid',0,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('LoginStatus','NotFound','Not Found',0,'','','','');

-- Option Set: UserLayoutDensity
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='UserLayoutDensity';
DELETE FROM sys_option_item WHERE option_set_code='UserLayoutDensity';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('UserLayoutDensity','User Layout Density','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('UserLayoutDensity','Compact','Compact',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('UserLayoutDensity','Comfortable','Comfortable',2,'','','','');

-- Option Set: TenantStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='TenantStatus';
DELETE FROM sys_option_item WHERE option_set_code='TenantStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('TenantStatus','Tenant Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TenantStatus','Draft','Draft',1,'','warning','clock','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TenantStatus','Active','Active',2,'','success','check','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TenantStatus','Suspended','Suspended',3,'','warning','pause','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TenantStatus','Closed','Closed',4,'','error','ban','');

-- Option Set: TenantLifecycle
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='TenantLifecycle';
DELETE FROM sys_option_item WHERE option_set_code='TenantLifecycle';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('TenantLifecycle','Tenant Lifecycle','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TenantLifecycle','Trial','Trial',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TenantLifecycle','Subscribed','Subscribed',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TenantLifecycle','GracePeriod','Grace Period',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TenantLifecycle','Offboarding','Offboarding',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TenantLifecycle','Archived','Archived',5,'','','','');

-- Option Set: TenantJobMode
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='TenantJobMode';
DELETE FROM sys_option_item WHERE option_set_code='TenantJobMode';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('TenantJobMode','Tenant Job Mode','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TenantJobMode','PerTenant','Per Tenant',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TenantJobMode','CrossTenant','Cross Tenant',2,'','','','');

-- Option Set: DocumentTemplateType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='DocumentTemplateType';
DELETE FROM sys_option_item WHERE option_set_code='DocumentTemplateType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('DocumentTemplateType','Document Template Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DocumentTemplateType','RichText','Rich Text',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DocumentTemplateType','Word','Word',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DocumentTemplateType','PDF','PDF',3,'','','','');

-- Option Set: SigningRequestStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='SigningRequestStatus';
DELETE FROM sys_option_item WHERE option_set_code='SigningRequestStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('SigningRequestStatus','Signing Request Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('SigningRequestStatus','Draft','Draft',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('SigningRequestStatus','Sent','Sent',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('SigningRequestStatus','InProgress','In Progress',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('SigningRequestStatus','Completed','Completed',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('SigningRequestStatus','Cancelled','Cancelled',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('SigningRequestStatus','Expired','Expired',6,'','','','');

-- Option Set: SigningDocumentStatus
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='SigningDocumentStatus';
DELETE FROM sys_option_item WHERE option_set_code='SigningDocumentStatus';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('SigningDocumentStatus','Signing Document Status','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('SigningDocumentStatus','Pending','Pending',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('SigningDocumentStatus','InProgress','In Progress',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('SigningDocumentStatus','Completed','Completed',3,'','','','');
