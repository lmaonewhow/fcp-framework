// Component contract checks extracted from the pinned template patch fragments.
// This is not Velocity rendering, a complete generated page, or a browser test.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import os from 'node:os';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const ts = require(process.argv[2] || 'typescript');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const fragments = JSON.parse(execFileSync(process.env.PYTHON || 'python', ['-c',
  'import sys,json;sys.path.insert(0,"tools");import native_generator_templates as t;print(json.dumps({"edit":t.EDIT_FORM,"payload":t.PAYLOAD,"split":t.NEW_SPLIT,"dayRange":t.DAY_RANGE_QUERY}))'], {cwd:root,encoding:'utf8'}));
// Select the standard single String checkbox branch for contract typechecking.
function select(source, checkboxEditable = true) {
  if (!checkboxEditable) source = source.replace(/#foreach[\s\S]*?#end\n#end/g, '');
  return source.split('\n').filter(line=>!line.trimStart().startsWith('#')).join('\n')
    .replaceAll('${BusinessName}','Example').replaceAll('${column.javaField}','tags');
}
const edit = select(fragments.edit).split('const initFormData:')[0];
const split = select(fragments.split);
const payload = select(fragments.payload);
const dayRange = fragments.dayRange.replaceAll('${AttrName}','EventTime');
const source = `
interface ExampleForm { id?: string; tags?: string; }
${edit}
const form: {value: ExampleEditForm} = {value:{tags:[]}};
function load(record: Partial<ExampleForm>) { ${split} }
function submit(): ExampleForm { ${payload} return payload; }
const dateRange: {value:[string,string]} = {value:['','']};
const datePickerValue: [string,string] = dateRange.value;
const dateRangeEventTime: {value:[string,string]|[]|null} = {value:['','']};
const queryParams = {value:{params:{} as Record<string,string|undefined>}};
// Boundary double for pinned addDateRange: it copies values, without fixing time-of-day.
const proxy = {addDateRange(query:typeof queryParams.value, range:string[], field:string) {
  query.params['begin'+field] = range[0]; query.params['end'+field] = range[1];
}};
function queryDate(input:[string,string]|[]|null) {
  dateRangeEventTime.value=input;
  ${dayRange}
  return queryParams.value.params;
}
`;
const temp=fs.mkdtempSync(path.join(os.tmpdir(),'fcp-generator-template-'));
try {
  const file=path.join(temp,'contract.ts');
  const check = content=>{
    fs.writeFileSync(file,content);
    const program=ts.createProgram([file],{strict:true,noEmit:true,target:ts.ScriptTarget.ES2022,skipLibCheck:true});
    return ts.getPreEmitDiagnostics(program).filter(d=>d.category===ts.DiagnosticCategory.Error);
  };
  const broken=check(`interface ExampleForm { tags?: string; }; const initial: ExampleForm={tags:[]};`);
  assert.ok(broken.length>0,'original checkbox array/string mismatch must fail');
  const errors=check(source);
  assert.equal(errors.length,0,ts.formatDiagnosticsWithColorAndContext(errors,{getCurrentDirectory:()=>root,getCanonicalFileName:x=>x,getNewLine:()=> '\n'}));
  const noEdit = select(fragments.edit,false).split('const initFormData:')[0];
  const noPayload = select(fragments.payload,false);
  assert.equal(check(`interface ExampleForm {id?:string;tags?:string;} ${noEdit}
const form:{value:ExampleEditForm}={value:{tags:'a'}}; function submit():ExampleForm {${noPayload} return payload;}`).length,0);
  assert.equal(select(fragments.split,false).trim(),'');
  console.log('PASS baseline checkbox type failure reproduced; editable and non-editable fragments strict-typecheck');
  const code=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;
  const context={};vm.createContext(context);vm.runInContext(code+';globalThis.contract={load,submit,form,queryDate};',context);
  const {load,submit,form,queryDate}=context.contract;
  for(const input of [undefined,'','a,b']) {
    load({tags:input});
    const before=JSON.stringify(form.value.tags);
    const one=submit();const two=submit();
    assert.equal(one.tags,input||'');assert.equal(two.tags,one.tags);
    assert.equal(JSON.stringify(form.value.tags),before);
    assert.ok(Array.isArray(form.value.tags));
  }
  load({tags:undefined});form.value.tags.push('new');assert.equal(submit().tags,'new');
  assert.deepEqual(Array.from(form.value.tags),['new']);
  console.log('PASS empty/edit checkbox decode, repeated submit and retry preserve array control state');
  for(const [input,day] of [
    [['2026-10-13 00:00:00','2026-10-13 00:00:00'],'2026-10-13'], // keyboard path
    [['2026-10-13 00:00:00','2026-10-13 23:59:59'],'2026-10-13'], // calendar path
    [['2026-10-13','2026-10-13'],'2026-10-13'],
    [['2024-02-29 12:34:56','2024-02-29 09:00:00'],'2024-02-29'],
    [['2026-11-01 00:00:00','2026-11-01 00:00:00'],'2026-11-01'], // DST day, no timezone math
  ]) {
    const before=JSON.stringify(input);Object.freeze(input);
    const result=queryDate(input);
    assert.equal(result.beginEventTime,day+' 00:00:00');
    assert.equal(result.endEventTime,day+' 23:59:59');
    assert.equal(JSON.stringify(input),before,'query must not mutate date controls');
    assert.ok(day+' 23:59:58' >= result.beginEventTime && day+' 23:59:58' <= result.endEventTime);
  }
  const spanning=queryDate(['2026-10-12 14:00:00','2026-10-14 02:00:00']);
  assert.equal(spanning.beginEventTime,'2026-10-12 00:00:00');
  assert.equal(spanning.endEventTime,'2026-10-14 23:59:59');
  for(const empty of [null,[],['',''],['2026-10-13 00:00:00','']]) {
    const cleared=queryDate(empty);
    assert.equal(cleared.beginEventTime,undefined);assert.equal(cleared.endEventTime,undefined);
  }
  console.log('PASS date-only keyboard/calendar full-day query bounds, late-evening inclusion, leap/DST dates and clear without control mutation');
  console.log('Full Velocity generation, full vue-tsc and browser: NOT_RUN');
} finally { fs.rmSync(temp,{recursive:true,force:true}); }
