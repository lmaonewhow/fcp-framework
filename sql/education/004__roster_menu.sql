-- Reviewed fresh isolated composition only. No role/user grants. Fixed IDs fail on collision.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time) VALUES
(986120,'教育关系名册',942000,21,'roster','fcp/roster/index','C','education:roster:view','education',1,1,'0','0',1,UTC_TIMESTAMP()),
(986100,'查看名册模块状态',986120,1,'',NULL,'F','education:roster:view','#',1,1,'0','0',1,UTC_TIMESTAMP()),
(986101,'查看本校学生关系',986120,2,'',NULL,'F','education:roster:student:read','#',1,1,'0','0',1,UTC_TIMESTAMP()),
(986102,'查看本校任教关系',986120,3,'',NULL,'F','education:roster:teacher:read','#',1,1,'0','0',1,UTC_TIMESTAMP());
