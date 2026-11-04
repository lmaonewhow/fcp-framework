-- Deployment identity only. Reserved IDs fail on collision; existing records are not replaced.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time) VALUES
(942010,'数据接入与业务插件',942000,2,'ingestion','fcp/ingestion/index','C','fcp:ingest:view','upload',1,1,'0','0',1,NOW()),
(942011,'导入与同步',942010,1,'',NULL,'F','fcp:ingest:import','#',1,1,'0','0',1,NOW()),
(942012,'启停输入及业务插件',942010,2,'',NULL,'F','fcp:ingest:manage','#',1,1,'0','0',1,NOW()),
(942013,'访问全部班级数据',942010,3,'',NULL,'F','fcp:ingest:allClasses','#',1,1,'0','0',1,NOW());
INSERT INTO sys_role_menu(role_id,menu_id) VALUES
(942101,942010),(942101,942011),(942101,942012),(942101,942013),
(942102,942010),(942102,942011),(942103,942010);
-- No accounts, default passwords or cross-tenant grants are introduced.
-- Teacher class assignments remain in fcp_eval_assignment and are rechecked before commit.
