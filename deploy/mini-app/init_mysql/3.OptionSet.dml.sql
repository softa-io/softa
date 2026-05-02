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

-- Option Set: WidgetType
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='WidgetType';
DELETE FROM sys_option_item WHERE option_set_code='WidgetType';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('WidgetType','Widget Type','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','URL','URL',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','CheckBox','Check Box',10,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','StatusBar','Status Bar',11,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Image','Single Image',12,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','MultiImage','Multi Image',13,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','yyyy-MM','Year-Month Picker',14,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','MM-dd','Month-Day Picker',15,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','HH:mm','Hour-Minute Picker',16,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','HH:mm:ss','Time Picker',17,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Email','Email',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Html','HTML',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Text','Text',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Color','Color Picker',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Monetary','Monetary',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Percentage','Percentage',7,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Slider','Slider',8,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('WidgetType','Radio','Radio Button',9,'','','','');

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

-- Option Set: Language
-- Clean up historical data
DELETE FROM sys_option_set WHERE option_set_code='Language';
DELETE FROM sys_option_item WHERE option_set_code='Language';
-- Insert option set
INSERT INTO sys_option_set(option_set_code,name,description) VALUES('Language','','');
-- Insert option set items
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Language','en-US','English (US)',1,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,item_name,sequence,parent_item_code,item_tone,item_icon,description) VALUES('Language','zh-CN','Chinese (Simplified) / 简体中文',2,'','','','');

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
