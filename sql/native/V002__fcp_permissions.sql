-- Apply exactly once through the deployment identity, never a user-controlled SQL endpoint.
-- These fixed IDs are reserved by this integration. Collisions stop installation; no REPLACE/IGNORE.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time) VALUES
('942000','FCP 工作台','0','1','fcp',NULL,'M','','education',1,1,'0','0',1,NOW()),
('942001','课堂评价','942000','1','school','fcp/school/index','C','fcp:eval:list','edit',1,1,'0','0',1,NOW()),
('942002','查看记录','942001','1','',NULL,'F','fcp:eval:get','#',1,1,'0','0',1,NOW()),
('942003','查看历史','942001','2','',NULL,'F','fcp:eval:history','#',1,1,'0','0',1,NOW()),
('942004','创建评价','942001','3','',NULL,'F','fcp:eval:create','#',1,1,'0','0',1,NOW()),
('942005','统计分析','942001','4','',NULL,'F','fcp:eval:analyze','#',1,1,'0','0',1,NOW()),
('942006','管理插件','942001','5','',NULL,'F','fcp:eval:manage','#',1,1,'0','0',1,NOW()),
('942007','全部班级','942001','6','',NULL,'F','fcp:eval:allClasses','#',1,1,'0','0',1,NOW()),
('942008','管理端界面','942001','7','',NULL,'F','fcp:ui:admin','#',1,1,'0','0',1,NOW());
INSERT INTO sys_role(role_id,tenant_id,role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time) VALUES
(942101,'000000','FCP 管理员','fcp_admin',10,'5','0','0',1,NOW()),
(942102,'000000','FCP 教师','fcp_teacher',11,'5','0','0',1,NOW()),
(942103,'000000','FCP 只读','fcp_viewer',12,'5','0','0',1,NOW());
INSERT INTO sys_role_menu(role_id,menu_id) VALUES
(942101,942000),
(942101,942001),
(942101,942002),
(942101,942003),
(942101,942004),
(942101,942005),
(942101,942006),
(942101,942007),
(942101,942008),
(942102,942000),
(942102,942001),
(942102,942002),
(942102,942003),
(942102,942004),
(942102,942005),
(942103,942000),
(942103,942001),
(942103,942002),
(942103,942003);
-- Use native user administration to create users and assign these roles.
-- A teacher also needs a row in fcp_eval_assignment, populated by the deployment/admin identity:
-- INSERT INTO fcp_eval_assignment VALUES ('tenant-000000','<actual numeric user ID>','class-a');
-- Separate tenants need their own roles, grants and tenant-package configuration. Never reuse a role across tenants.
