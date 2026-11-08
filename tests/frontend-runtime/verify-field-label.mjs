/** Actual shared renderer + installed native UI components. SSR structure, not browser AX acceptance. */
import {readFileSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
const project=process.argv[2],shell=process.argv[3];
if(!project||!['admin','teacher'].includes(shell))throw new Error('Usage: node verify-field-label.mjs <native project> admin|teacher');
const require=createRequire(join(resolve(project),'package.json'));
const {createSSRApp,h,ref}=require('vue');
const {renderToString}=require('@vue/server-renderer');
const ts=require('typescript');
const components=shell==='teacher'?require('naive-ui'):require('element-plus');
const input=shell==='teacher'?components.NInput:components.ElInput;
const button=shell==='teacher'?components.NButton:components.ElButton;
const source=readFileSync(process.env.FCP_FIELD_SOURCE ?? 'integration/frontend-shared/PluginWorkspace.vue','utf8');
const actual=source.slice(source.indexOf('function renderNode('),source.indexOf('const Configuration ='));
assert.ok(actual.startsWith('function renderNode('));
const compiled=ts.transpileModule(actual,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ES2022}}).outputText;
const adapterSource=readFileSync('integration/'+(shell==='teacher'?'soybean':'plus-ui')+'/src/views/fcp/plugins/index.vue','utf8');
const attributeExpression=adapterSource.match(/fieldAttributes: (.+),\n/);
assert.ok(attributeExpression,'Actual native adapter exposes input attribute mapping');
const fieldAttributes=new Function('return ('+attributeExpression[1]+');')();
const props={host:{input,button,fieldAttributes,fieldValueProp:shell==='teacher'?'value':'modelValue',fieldUpdateProp:shell==='teacher'?'onUpdate:value':'onUpdate:modelValue'}};
const busy=ref(false),pending=ref(null),uncertainty=ref(false);
const render=new Function('h','props','fields','busy','pending','uncertainty','edit','results','invoke',compiled+';return renderNode;')(h,props,ref({}),busy,pending,uncertainty,()=>{},ref({}),()=>{});
const cases=[
  {name:'normal',busy:false,pending:null,uncertainty:false,disabled:false},
  {name:'uncertain',busy:false,pending:null,uncertainty:true,disabled:true},
  {name:'pending',busy:false,pending:{key:'test-only'},uncertainty:false,disabled:true},
  {name:'busy',busy:true,pending:null,uncertainty:false,disabled:true}
];
for(const state of cases){
  busy.value=state.busy;pending.value=state.pending;uncertainty.value=state.uncertainty;
const html=await renderToString(createSSRApp({render:()=>render({type:'field',field:'message',text:'Message'},'ci.python:demo')}));
assert.equal((html.match(/<label\b/g)||[]).length,1,'One native implicit label');
assert.match(html,/<span>Message<\/span>/,'One visible field name');
assert.equal((html.match(/<input\b/g)||[]).length,1,'One real input');
const inputTags=html.match(/<input\b[^>]*>/g);
assert.match(inputTags[0],/aria-label="Message"/,'Actual native input must carry explicit accessible name');
assert.equal((html.match(/aria-label="Message"/g)||[]).length,1,'Exactly one accessible label on the real input');
assert.doesNotMatch(html,/<(?!input\b)[a-z][^>]*aria-label="Message"/,'No wrapper aria-label duplication');
assert.match(html,/<label\b[\s\S]*<input\b[\s\S]*<\/label>/,'Real input is within its native implicit label');
assert.equal(/\sdisabled(?:[=\s>])/.test(inputTags[0]),state.disabled,`${state.name}: real native input disabled state`);
const buttonHtml=await renderToString(createSSRApp({render:()=>render({type:'button',text:'Run',capabilityId:'fixture.invoke'},'ci.python:demo')}));
const buttons=buttonHtml.match(/<button\b[^>]*>/g)??[];
assert.equal(buttons.length,1,`${state.name}: one native action button`);
assert.equal(/\sdisabled(?:[=\s>])/.test(buttons[0]),state.disabled,`${state.name}: real native button disabled state`);
}
console.log(JSON.stringify({shell,status:'PASS',states:cases.map(state=>state.name),scope:'Actual shared renderNode with installed UI component and Vue SSR; not browser accessibility-tree acceptance'}));
