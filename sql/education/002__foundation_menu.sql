-- Reviewed fresh isolated composition only. Does not grant any role or user.
-- Fixed IDs fail on collision; never REPLACE or INSERT IGNORE.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time) VALUES
(986020,'教育基础资料',942000,20,'education','fcp/education/index','C','education:foundation:view','education',1,1,'0','0',1,UTC_TIMESTAMP()),
(986000,'查看教育模块状态',986020,1,'',NULL,'F','education:foundation:view','#',1,1,'0','0',1,UTC_TIMESTAMP()),
(986001,'读取教育基础资料',986020,2,'',NULL,'F','education:foundation:read','#',1,1,'0','0',1,UTC_TIMESTAMP()),
(986002,'新建组织',986020,3,'',NULL,'F','education:foundation:org:create','#',1,1,'0','0',1,UTC_TIMESTAMP()),
(986003,'修改组织名称',986020,4,'',NULL,'F','education:foundation:org:rename','#',1,1,'0','0',1,UTC_TIMESTAMP()),
(986004,'新建班级',986020,5,'',NULL,'F','education:foundation:class:create','#',1,1,'0','0',1,UTC_TIMESTAMP()),
(986005,'修改班级名称',986020,6,'',NULL,'F','education:foundation:class:rename','#',1,1,'0','0',1,UTC_TIMESTAMP()),
(986006,'新建学校',986020,7,'',NULL,'F','education:foundation:school:create','#',1,1,'0','0',1,UTC_TIMESTAMP());
