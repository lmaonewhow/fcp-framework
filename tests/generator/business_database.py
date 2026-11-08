"""Fail-closed SQL acceptance for one owned synthetic fixture, never application code."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess

COLUMNS = 'menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark'
TITLE = '生成器合成验收'


def require_owned(database, owned_database):
    if os.environ.get('GITHUB_ACTIONS') != 'true' or database != owned_database or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', database):
        raise RuntimeError('Generated SQL requires the exact freshly created disposable CI database')


def validate_menu_sql(source):
    """Accept exactly the six pinned INSERTs with fixed fixture values. No generic SQL parser."""
    uncommented = '\n'.join(line for line in source.splitlines() if not line.lstrip().startswith('--'))
    parts = [part.strip() for part in uncommented.split(';')]
    if len(parts) != 7 or parts[-1]:
        raise RuntimeError('Only the six synthetic native menu INSERTs are allowed')
    rows = []
    prefix = r'insert\s+into\s+sys_menu\s*\(\s*' + r'\s*,\s*'.join(COLUMNS.split(', ')) + r'\s*\)\s*values\((.*)\)'
    for part in parts[:-1]:
        match = re.fullmatch(prefix, part, re.DOTALL | re.IGNORECASE)
        if not match:
            raise RuntimeError('Generated SQL statement/table/columns are outside the fixed fixture')
        values = [value.strip() for value in match[1].split(',')]
        if len(values) != 19 or not re.fullmatch(r'[1-9][0-9]{0,18}', values[0]):
            raise RuntimeError('Invalid generated menu row')
        rows.append(values)
    ids = [row[0] for row in rows]
    if len(set(ids)) != 6 or any(int(value) > 9223372036854775807 for value in ids):
        raise RuntimeError('Generated menu IDs are invalid or duplicated')
    for index, (action, suffix) in enumerate(zip(('list','query','add','edit','remove','export'), ('','查询','新增','修改','删除','导出'))):
        expected = [ids[index], "'" + TITLE + suffix + "'", "'3'" if index == 0 else ids[0], "'"+str(max(1,index))+"'", "'probe'" if index == 0 else "'#'", "'generatorprobe/probe/index'" if index == 0 else "''", '1','0', "'C'" if index == 0 else "'F'", "'0'", "'0'", "'generatorprobe:probe:" + action + "'", "'#'", '103','1','sysdate()','null','null', "'"+TITLE+"菜单'" if index == 0 else "''"]
        if rows[index] != expected:
            raise RuntimeError('Generated menu SQL differs from the fixed reviewed synthetic plan')
    return ids


def install_generated_menu(fixture, source: Path, output: Path, mysql_defaults: Path, owned_database: str, execution_started=None):
    require_owned(fixture.database, owned_database)
    if source.is_symlink() or not source.is_file() or source.stat().st_size > 32768:
        raise RuntimeError('Unsafe generated SQL source')
    contents = source.read_text()
    ids = validate_menu_sql(contents)
    database = fixture.database
    query = lambda text: fixture.sql(f'USE `{database}`; ' + text).strip()
    id_list = ','.join(ids)
    def preflight():
        if query('SELECT DATABASE();') != database:
            raise RuntimeError('Generated SQL selected a different schema')
        if query(f"SELECT COUNT(*) FROM sys_menu WHERE menu_id IN ({id_list}) OR perms LIKE 'generatorprobe:probe:%' OR component='generatorprobe/probe/index';") != '0':
            raise RuntimeError('Generated menu collision or existing fixture state')
        if query('SELECT COUNT(*) FROM fcp_codegen_probe;') != '0':
            raise RuntimeError('Synthetic generated business table is not empty')
        if query("SELECT COUNT(*) FROM sys_menu WHERE menu_id=3 AND path='tool' AND menu_type='M';") != '1':
            raise RuntimeError('Pinned native tool menu parent differs')
        if query("SELECT COUNT(*) FROM sys_role WHERE role_id=942103 AND tenant_id='000000' AND role_key='fcp_viewer';") != '1':
            raise RuntimeError('Native read-only fixture role is not owned/expected')
        if query('SELECT COUNT(*) FROM sys_role_menu WHERE role_id=942103 AND menu_id IN (3,942008);') != '0':
            raise RuntimeError('Synthetic read-only role already has unexpected grants')
    preflight()
    plan = {'status':'PLANNED','database':database,'generatedSqlSha256':hashlib.sha256(source.read_bytes()).hexdigest(),
            'menuIds':ids,'menuInsertCount':6,'permissions':'admin retains native permission rules; existing synthetic viewer receives menu/list/query/admin-shell only',
            'tables':['sys_menu','sys_role_menu','fcp_codegen_probe'],'backup':'menu-before.sql','production':'NOT_RUN'}
    plan_path = output/'menu-plan.json'
    plan_path.write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n')
    backup = output/'menu-before.sql'
    command = ['mysqldump','--defaults-extra-file='+str(mysql_defaults),'--single-transaction','--no-tablespaces','--set-gtid-purged=OFF','--skip-comments',database,'sys_menu','sys_role_menu','fcp_codegen_probe']
    with backup.open('w') as stream:
        result = subprocess.run(command,stdout=stream,stderr=subprocess.PIPE,text=True,timeout=60)
    from ci_ingestion import safe_diagnostic
    (output/'menu-backup.log').write_text('mysqldump exit='+str(result.returncode)+'\n'+safe_diagnostic(getattr(result,'stderr',''),getattr(fixture,'diagnostic_secrets',[]))+'\n')
    if result.returncode or not backup.stat().st_size:
        raise RuntimeError('Disposable generated-menu backup failed; SQL was not executed')
    plan.update(backupSha256=hashlib.sha256(backup.read_bytes()).hexdigest(),backupExitCode=result.returncode,status='BACKED_UP')
    plan_path.write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n')
    preflight()
    if hashlib.sha256(source.read_bytes()).hexdigest() != plan['generatedSqlSha256']:
        raise RuntimeError('Generated SQL changed after plan/backup')
    # All statements are INSERTs into InnoDB tables; execution is acceptance-only.
    grants = f'INSERT INTO sys_role_menu(role_id,menu_id) VALUES (942103,3),(942103,942008),(942103,{ids[0]}),(942103,{ids[1]});'
    foreign = "INSERT INTO fcp_codegen_probe(id,tenant_id,name,event_time,tags) VALUES(970001,'fcp-other-ci','其他租户合成记录','2026-10-15 12:00:00','0');"
    if execution_started: execution_started()
    query('START TRANSACTION;\n'+contents+'\n'+grants+'\n'+foreign+'\nCOMMIT;')
    if query(f'SELECT COUNT(*) FROM sys_menu WHERE menu_id IN ({id_list});') != '6':
        raise RuntimeError('Generated menu SQL postcondition failed')
    plan.update(status='PASS',executedStatements=8,generatedSqlExecution='PASS')
    plan_path.write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n')
    return plan
