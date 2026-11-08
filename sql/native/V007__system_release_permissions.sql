-- Deployment identity only. Fail on reserved ID collision; no automatic role grants.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time) VALUES
(942030,'系统版本与发布',942000,4,'releases','fcp/releases/index','C','fcp:release:view','upload',1,1,'0','0',1,NOW()),
(942031,'管理系统发布',942030,1,'',NULL,'F','fcp:release:manage','#',1,1,'0','0',1,NOW());
-- Release registry and control journals reuse fcp_runtime_state with dedicated IDs.
-- Runtime DML identity must also SELECT the migration ledger; grant separately through deployment review.
