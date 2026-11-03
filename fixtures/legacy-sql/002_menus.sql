-- RuoYi-Vue-Plus 5.4.1 only. Run on an isolated test DB after checking reserved IDs 910000..910024.
-- A collision is an error; existing menus/roles are never overwritten or deleted.
START TRANSACTION;
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon) VALUES
('910000','FCP 双业务','0','90','fcp',NULL,'M','0','0','fcp:dual:view','component'),
('910001','采购审批','910000','1','purchase','fcp/purchase/index','C','0','0','fcp:purchase:list','shopping'),
('910002','报修处理','910000','2','repair','fcp/repair/index','C','0','0','fcp:repair:list','tool'),
('910010','fcp:dual:manage','910000','99','#',NULL,'F','0','0','fcp:dual:manage','#'),
('910011','fcp:purchase:create','910001','99','#',NULL,'F','0','0','fcp:purchase:create','#'),
('910012','fcp:purchase:get','910001','99','#',NULL,'F','0','0','fcp:purchase:get','#'),
('910013','fcp:purchase:history','910001','99','#',NULL,'F','0','0','fcp:purchase:history','#'),
('910014','fcp:purchase:submit','910001','99','#',NULL,'F','0','0','fcp:purchase:submit','#'),
('910015','fcp:purchase:approve','910001','99','#',NULL,'F','0','0','fcp:purchase:approve','#'),
('910016','fcp:purchase:reject','910001','99','#',NULL,'F','0','0','fcp:purchase:reject','#'),
('910017','fcp:purchase:listAll','910001','99','#',NULL,'F','0','0','fcp:purchase:listAll','#'),
('910018','fcp:repair:create','910002','99','#',NULL,'F','0','0','fcp:repair:create','#'),
('910019','fcp:repair:get','910002','99','#',NULL,'F','0','0','fcp:repair:get','#'),
('910020','fcp:repair:history','910002','99','#',NULL,'F','0','0','fcp:repair:history','#'),
('910021','fcp:repair:assign','910002','99','#',NULL,'F','0','0','fcp:repair:assign','#'),
('910022','fcp:repair:resolve','910002','99','#',NULL,'F','0','0','fcp:repair:resolve','#'),
('910023','fcp:repair:close','910002','99','#',NULL,'F','0','0','fcp:repair:close','#'),
('910024','fcp:repair:listAll','910002','99','#',NULL,'F','0','0','fcp:repair:listAll','#');
COMMIT;
