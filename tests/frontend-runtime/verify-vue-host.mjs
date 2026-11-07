import {mkdtempSync,readFileSync,writeFileSync,symlinkSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {createRequire} from 'node:module';
import {spawnSync} from 'node:child_process';
const project=process.argv[2];if(!project)throw new Error('Usage: node tests/frontend-runtime/verify-vue-host.mjs <assembled native frontend>');
const modules=resolve(project,'node_modules');const require=createRequire(join(modules,'typescript','package.json'));const ts=require('./lib/typescript.js');
const temp=mkdtempSync(join(tmpdir(),'fcp-vue-unit-'));
try {
 symlinkSync(modules,join(temp,'node_modules'),'dir');
 for(const [source,name] of [['integration/frontend-runtime/index.ts','runtime'],['integration/frontend-shared/usePlugins.ts','usePlugins'],['integration/frontend-shared/usePluginManager.ts','usePluginManager'],['integration/frontend-shared/useVersionManager.ts','useVersionManager']]) {
   const code=ts.transpileModule(readFileSync(source,'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ES2022}}).outputText.replaceAll("from './runtime'","from './runtime.mjs'");
   writeFileSync(join(temp,name+'.mjs'),code);
 }
 const testFile=process.argv[3]==='version'?'vue-version.test.mjs':process.argv[3]==='manager'?'vue-manager.test.mjs':'vue-host.test.mjs';
 writeFileSync(join(temp,testFile),readFileSync('tests/frontend-runtime/'+testFile));
 const result=spawnSync(process.execPath,['--test',join(temp,testFile)],{stdio:'inherit'});process.exitCode=result.status??1;
} finally {rmSync(temp,{recursive:true,force:true});}
