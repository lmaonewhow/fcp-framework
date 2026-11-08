-- V009: remove only untouched upstream demo/website seed menus, never business data.
-- Source: locked RuoYi-Vue-Plus 5.6.2 script/sql/ry_vue_5.X.sql.
-- Fresh empty-schema installer only. Existing installations: review docs/MIGRATIONS.md.
-- Run with exclusive maintenance access, on one connection, using MySQL 8.
-- Temporary tables are connection-local. No native accounts, roles, organizations,
-- test tables or business records are deleted. Unknown/customized menus survive.
START TRANSACTION;
CREATE TEMPORARY TABLE fcp_v009_candidates (menu_id BIGINT PRIMARY KEY);
INSERT INTO fcp_v009_candidates(menu_id)
SELECT m.menu_id FROM sys_menu m WHERE
(m.menu_id <=> '4' AND m.menu_name <=> BINARY 'PLUS官网' AND m.parent_id <=> '0' AND m.order_num <=> '5' AND m.path <=> BINARY 'https://gitee.com/dromara/RuoYi-Vue-Plus' AND m.component <=> null AND m.query_param <=> BINARY '' AND m.is_frame <=> 0 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'M' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY '' AND m.icon <=> BINARY 'guide' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY 'RuoYi-Vue-Plus官网地址')
 OR (m.menu_id <=> '5' AND m.menu_name <=> BINARY '测试菜单' AND m.parent_id <=> '0' AND m.order_num <=> '5' AND m.path <=> BINARY 'demo' AND m.component <=> null AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'M' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY '' AND m.icon <=> BINARY 'star' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '测试菜单')
 OR (m.menu_id <=> '1500' AND m.menu_name <=> BINARY '测试单表' AND m.parent_id <=> '5' AND m.order_num <=> '1' AND m.path <=> BINARY 'demo' AND m.component <=> BINARY 'demo/demo/index' AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'C' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY 'demo:demo:list' AND m.icon <=> BINARY '#' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '测试单表菜单')
 OR (m.menu_id <=> '1501' AND m.menu_name <=> BINARY '测试单表查询' AND m.parent_id <=> '1500' AND m.order_num <=> '1' AND m.path <=> BINARY '#' AND m.component <=> BINARY '' AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'F' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY 'demo:demo:query' AND m.icon <=> BINARY '#' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '')
 OR (m.menu_id <=> '1502' AND m.menu_name <=> BINARY '测试单表新增' AND m.parent_id <=> '1500' AND m.order_num <=> '2' AND m.path <=> BINARY '#' AND m.component <=> BINARY '' AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'F' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY 'demo:demo:add' AND m.icon <=> BINARY '#' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '')
 OR (m.menu_id <=> '1503' AND m.menu_name <=> BINARY '测试单表修改' AND m.parent_id <=> '1500' AND m.order_num <=> '3' AND m.path <=> BINARY '#' AND m.component <=> BINARY '' AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'F' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY 'demo:demo:edit' AND m.icon <=> BINARY '#' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '')
 OR (m.menu_id <=> '1504' AND m.menu_name <=> BINARY '测试单表删除' AND m.parent_id <=> '1500' AND m.order_num <=> '4' AND m.path <=> BINARY '#' AND m.component <=> BINARY '' AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'F' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY 'demo:demo:remove' AND m.icon <=> BINARY '#' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '')
 OR (m.menu_id <=> '1505' AND m.menu_name <=> BINARY '测试单表导出' AND m.parent_id <=> '1500' AND m.order_num <=> '5' AND m.path <=> BINARY '#' AND m.component <=> BINARY '' AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'F' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY 'demo:demo:export' AND m.icon <=> BINARY '#' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '')
 OR (m.menu_id <=> '1506' AND m.menu_name <=> BINARY '测试树表' AND m.parent_id <=> '5' AND m.order_num <=> '1' AND m.path <=> BINARY 'tree' AND m.component <=> BINARY 'demo/tree/index' AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'C' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY 'demo:tree:list' AND m.icon <=> BINARY '#' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '测试树表菜单')
 OR (m.menu_id <=> '1507' AND m.menu_name <=> BINARY '测试树表查询' AND m.parent_id <=> '1506' AND m.order_num <=> '1' AND m.path <=> BINARY '#' AND m.component <=> BINARY '' AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'F' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY 'demo:tree:query' AND m.icon <=> BINARY '#' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '')
 OR (m.menu_id <=> '1508' AND m.menu_name <=> BINARY '测试树表新增' AND m.parent_id <=> '1506' AND m.order_num <=> '2' AND m.path <=> BINARY '#' AND m.component <=> BINARY '' AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'F' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY 'demo:tree:add' AND m.icon <=> BINARY '#' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '')
 OR (m.menu_id <=> '1509' AND m.menu_name <=> BINARY '测试树表修改' AND m.parent_id <=> '1506' AND m.order_num <=> '3' AND m.path <=> BINARY '#' AND m.component <=> BINARY '' AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'F' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY 'demo:tree:edit' AND m.icon <=> BINARY '#' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '')
 OR (m.menu_id <=> '1510' AND m.menu_name <=> BINARY '测试树表删除' AND m.parent_id <=> '1506' AND m.order_num <=> '4' AND m.path <=> BINARY '#' AND m.component <=> BINARY '' AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'F' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY 'demo:tree:remove' AND m.icon <=> BINARY '#' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '')
 OR (m.menu_id <=> '1511' AND m.menu_name <=> BINARY '测试树表导出' AND m.parent_id <=> '1506' AND m.order_num <=> '5' AND m.path <=> BINARY '#' AND m.component <=> BINARY '' AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'F' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY 'demo:tree:export' AND m.icon <=> BINARY '#' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '');
-- Non-canonical package lists need human review. Preserve all menus and references
-- rather than guessing how malformed/whitespace/zero-padded IDs should be interpreted.
-- Explicitly reject non-CSV characters: ICU $ can match before a final newline.
DELETE FROM fcp_v009_candidates WHERE EXISTS (
 SELECT 1 FROM sys_tenant_package WHERE menu_ids IS NOT NULL AND menu_ids <> ''
 AND (REGEXP_LIKE(menu_ids, '[^0-9,]', 'c')
 OR NOT REGEXP_LIKE(menu_ids, '^(0|[1-9][0-9]*)(,(0|[1-9][0-9]*))*$', 'c'))
);
CREATE TEMPORARY TABLE fcp_v009_snapshot (menu_id BIGINT PRIMARY KEY);
-- Ancestor identity pass 1/2: a reused/customized parent protects its original descendants.
DELETE FROM fcp_v009_snapshot;
INSERT INTO fcp_v009_snapshot SELECT menu_id FROM fcp_v009_candidates;
DELETE c FROM fcp_v009_candidates c
 JOIN sys_menu m ON m.menu_id = c.menu_id
 LEFT JOIN fcp_v009_snapshot p ON p.menu_id = m.parent_id
 WHERE m.parent_id IN (5,1500,1506) AND p.menu_id IS NULL;
-- Ancestor identity pass 2/2: a reused/customized parent protects its original descendants.
DELETE FROM fcp_v009_snapshot;
INSERT INTO fcp_v009_snapshot SELECT menu_id FROM fcp_v009_candidates;
DELETE c FROM fcp_v009_candidates c
 JOIN sys_menu m ON m.menu_id = c.menu_id
 LEFT JOIN fcp_v009_snapshot p ON p.menu_id = m.parent_id
 WHERE m.parent_id IN (5,1500,1506) AND p.menu_id IS NULL;
-- Descendant protection pass 1/3: keep every ancestor of any non-removable child.
-- The allowlist has at most three levels; custom subtrees are never traversed/deleted.
DELETE FROM fcp_v009_snapshot;
INSERT INTO fcp_v009_snapshot SELECT menu_id FROM fcp_v009_candidates;
DELETE c FROM fcp_v009_candidates c
 JOIN sys_menu child ON child.parent_id = c.menu_id
 LEFT JOIN fcp_v009_snapshot removable ON removable.menu_id = child.menu_id
 WHERE removable.menu_id IS NULL;
-- Descendant protection pass 2/3: keep every ancestor of any non-removable child.
-- The allowlist has at most three levels; custom subtrees are never traversed/deleted.
DELETE FROM fcp_v009_snapshot;
INSERT INTO fcp_v009_snapshot SELECT menu_id FROM fcp_v009_candidates;
DELETE c FROM fcp_v009_candidates c
 JOIN sys_menu child ON child.parent_id = c.menu_id
 LEFT JOIN fcp_v009_snapshot removable ON removable.menu_id = child.menu_id
 WHERE removable.menu_id IS NULL;
-- Descendant protection pass 3/3: keep every ancestor of any non-removable child.
-- The allowlist has at most three levels; custom subtrees are never traversed/deleted.
DELETE FROM fcp_v009_snapshot;
INSERT INTO fcp_v009_snapshot SELECT menu_id FROM fcp_v009_candidates;
DELETE c FROM fcp_v009_candidates c
 JOIN sys_menu child ON child.parent_id = c.menu_id
 LEFT JOIN fcp_v009_snapshot removable ON removable.menu_id = child.menu_id
 WHERE removable.menu_id IS NULL;
-- Remove only references to confirmed, removable seed menus.
DELETE rm FROM sys_role_menu rm JOIN fcp_v009_candidates c ON c.menu_id = rm.menu_id;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 4
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',4(?=,)', ''))
 WHERE FIND_IN_SET('4', p.menu_ids) > 0;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 5
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',5(?=,)', ''))
 WHERE FIND_IN_SET('5', p.menu_ids) > 0;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 1500
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',1500(?=,)', ''))
 WHERE FIND_IN_SET('1500', p.menu_ids) > 0;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 1501
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',1501(?=,)', ''))
 WHERE FIND_IN_SET('1501', p.menu_ids) > 0;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 1502
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',1502(?=,)', ''))
 WHERE FIND_IN_SET('1502', p.menu_ids) > 0;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 1503
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',1503(?=,)', ''))
 WHERE FIND_IN_SET('1503', p.menu_ids) > 0;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 1504
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',1504(?=,)', ''))
 WHERE FIND_IN_SET('1504', p.menu_ids) > 0;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 1505
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',1505(?=,)', ''))
 WHERE FIND_IN_SET('1505', p.menu_ids) > 0;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 1506
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',1506(?=,)', ''))
 WHERE FIND_IN_SET('1506', p.menu_ids) > 0;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 1507
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',1507(?=,)', ''))
 WHERE FIND_IN_SET('1507', p.menu_ids) > 0;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 1508
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',1508(?=,)', ''))
 WHERE FIND_IN_SET('1508', p.menu_ids) > 0;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 1509
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',1509(?=,)', ''))
 WHERE FIND_IN_SET('1509', p.menu_ids) > 0;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 1510
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',1510(?=,)', ''))
 WHERE FIND_IN_SET('1510', p.menu_ids) > 0;
UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = 1511
 SET p.menu_ids = TRIM(BOTH ',' FROM REGEXP_REPLACE(CONCAT(',', p.menu_ids, ','), ',1511(?=,)', ''))
 WHERE FIND_IN_SET('1511', p.menu_ids) > 0;
DELETE m FROM sys_menu m JOIN fcp_v009_candidates c ON c.menu_id = m.menu_id;
-- The locked workflow seed attaches real leave requests under the old demo root.
-- Rename the display label only when the entire surviving subtree is untouched.
-- Snapshot the decision before UPDATE to avoid MySQL target-table subquery rules.
DELETE FROM fcp_v009_snapshot;
INSERT INTO fcp_v009_snapshot(menu_id)
SELECT m.menu_id FROM sys_menu m WHERE
m.menu_id <=> '5' AND m.menu_name <=> BINARY '测试菜单' AND m.parent_id <=> '0' AND m.order_num <=> '5' AND m.path <=> BINARY 'demo' AND m.component <=> null AND m.query_param <=> BINARY '' AND m.is_frame <=> 1 AND m.is_cache <=> 0 AND m.menu_type <=> BINARY 'M' AND m.visible <=> BINARY '0' AND m.status <=> BINARY '0' AND m.perms <=> BINARY '' AND m.icon <=> BINARY 'star' AND m.create_dept <=> 103 AND m.create_by <=> 1 AND m.update_by <=> null AND m.update_time <=> null AND m.remark <=> BINARY '测试菜单'
 AND (SELECT COUNT(*) FROM sys_menu w WHERE
(w.menu_id <=> '11638' AND w.menu_name <=> BINARY '请假申请' AND w.parent_id <=> 5 AND w.order_num <=> 1 AND w.path <=> BINARY 'leave' AND w.component <=> BINARY 'workflow/leave/index' AND w.query_param <=> BINARY '' AND w.is_frame <=> 1 AND w.is_cache <=> 0 AND w.menu_type <=> BINARY 'C' AND w.visible <=> BINARY '0' AND w.status <=> BINARY '0' AND w.perms <=> BINARY 'workflow:leave:list' AND w.icon <=> BINARY '#' AND w.create_dept <=> 103 AND w.create_by <=> 1 AND w.update_by <=> null AND w.update_time <=> null AND w.remark <=> BINARY '请假申请菜单')
 OR (w.menu_id <=> '11639' AND w.menu_name <=> BINARY '请假申请查询' AND w.parent_id <=> '11638' AND w.order_num <=> 1 AND w.path <=> BINARY '#' AND w.component <=> BINARY '' AND w.query_param <=> BINARY '' AND w.is_frame <=> 1 AND w.is_cache <=> 0 AND w.menu_type <=> BINARY 'F' AND w.visible <=> BINARY '0' AND w.status <=> BINARY '0' AND w.perms <=> BINARY 'workflow:leave:query' AND w.icon <=> BINARY '#' AND w.create_dept <=> 103 AND w.create_by <=> 1 AND w.update_by <=> null AND w.update_time <=> null AND w.remark <=> BINARY '')
 OR (w.menu_id <=> '11640' AND w.menu_name <=> BINARY '请假申请新增' AND w.parent_id <=> '11638' AND w.order_num <=> 2 AND w.path <=> BINARY '#' AND w.component <=> BINARY '' AND w.query_param <=> BINARY '' AND w.is_frame <=> 1 AND w.is_cache <=> 0 AND w.menu_type <=> BINARY 'F' AND w.visible <=> BINARY '0' AND w.status <=> BINARY '0' AND w.perms <=> BINARY 'workflow:leave:add' AND w.icon <=> BINARY '#' AND w.create_dept <=> 103 AND w.create_by <=> 1 AND w.update_by <=> null AND w.update_time <=> null AND w.remark <=> BINARY '')
 OR (w.menu_id <=> '11641' AND w.menu_name <=> BINARY '请假申请修改' AND w.parent_id <=> '11638' AND w.order_num <=> 3 AND w.path <=> BINARY '#' AND w.component <=> BINARY '' AND w.query_param <=> BINARY '' AND w.is_frame <=> 1 AND w.is_cache <=> 0 AND w.menu_type <=> BINARY 'F' AND w.visible <=> BINARY '0' AND w.status <=> BINARY '0' AND w.perms <=> BINARY 'workflow:leave:edit' AND w.icon <=> BINARY '#' AND w.create_dept <=> 103 AND w.create_by <=> 1 AND w.update_by <=> null AND w.update_time <=> null AND w.remark <=> BINARY '')
 OR (w.menu_id <=> '11642' AND w.menu_name <=> BINARY '请假申请删除' AND w.parent_id <=> '11638' AND w.order_num <=> 4 AND w.path <=> BINARY '#' AND w.component <=> BINARY '' AND w.query_param <=> BINARY '' AND w.is_frame <=> 1 AND w.is_cache <=> 0 AND w.menu_type <=> BINARY 'F' AND w.visible <=> BINARY '0' AND w.status <=> BINARY '0' AND w.perms <=> BINARY 'workflow:leave:remove' AND w.icon <=> BINARY '#' AND w.create_dept <=> 103 AND w.create_by <=> 1 AND w.update_by <=> null AND w.update_time <=> null AND w.remark <=> BINARY '')
 OR (w.menu_id <=> '11643' AND w.menu_name <=> BINARY '请假申请导出' AND w.parent_id <=> '11638' AND w.order_num <=> 5 AND w.path <=> BINARY '#' AND w.component <=> BINARY '' AND w.query_param <=> BINARY '' AND w.is_frame <=> 1 AND w.is_cache <=> 0 AND w.menu_type <=> BINARY 'F' AND w.visible <=> BINARY '0' AND w.status <=> BINARY '0' AND w.perms <=> BINARY 'workflow:leave:export' AND w.icon <=> BINARY '#' AND w.create_dept <=> 103 AND w.create_by <=> 1 AND w.update_by <=> null AND w.update_time <=> null AND w.remark <=> BINARY '')) = 6
 AND NOT EXISTS (SELECT 1 FROM sys_menu child WHERE child.parent_id = 5 AND child.menu_id <> 11638)
 AND NOT EXISTS (SELECT 1 FROM sys_menu child WHERE child.parent_id = 11638 AND child.menu_id NOT IN (11639,11640,11641,11642,11643))
 AND NOT EXISTS (SELECT 1 FROM sys_menu child WHERE child.parent_id IN (11639,11640,11641,11642,11643));
UPDATE sys_menu m JOIN fcp_v009_snapshot approved ON approved.menu_id = m.menu_id
 SET m.menu_name = '日常事务';
DROP TEMPORARY TABLE fcp_v009_snapshot;
DROP TEMPORARY TABLE fcp_v009_candidates;
COMMIT;
