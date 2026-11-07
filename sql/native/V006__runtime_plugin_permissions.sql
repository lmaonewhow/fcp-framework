-- Deployment identity only; reserved menu IDs fail on collision.
-- No role grants, users or default passwords. Reserved IDs fail on collision.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time) VALUES
(942020,'运行时插件',942000,3,'plugins','fcp/plugins/index','C','fcp:plugin:view','component',1,1,'0','0',1,NOW()),
(942021,'管理运行时插件',942020,1,'',NULL,'F','fcp:plugin:manage','#',1,1,'0','0',1,NOW()),
(942022,'安装运行时插件',942020,2,'',NULL,'F','fcp:plugin:install','#',1,1,'0','0',1,NOW());
-- Deliberately no uninstall DELETE/cascade: records, schemas, receipts and audit survive uninstall.
