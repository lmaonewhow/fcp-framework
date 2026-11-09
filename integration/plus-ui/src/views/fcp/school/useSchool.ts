import {onBeforeUnmount,onMounted,reactive,ref} from 'vue';
import {createSchoolBridge,type RecordRow,type Snapshot,type EvaluationData} from './bridge';
import {transport} from './transport';
/** A failed write keeps its request key for a same-payload retry. Never silently retries writes. */
export function useSchool(shell:'admin'|'teacher'){
  const api=createSchoolBridge(transport),state=ref<Snapshot|null>(null),rows=ref<RecordRow[]>([]),error=ref(''),summary=ref(''),busy=ref(false);
  const form=reactive<EvaluationData>({student:'',classId:'',indicator:'课堂参与',score:80,comment:''});
  let alive=true,pending:{fingerprint:string;key:string}|null=null;
  const nextCursor=ref<string|null>(null),savedRecord=ref<RecordRow|null>(null);
  async function refresh(cursor=''){
    const snapshot=await api.refresh();
    if(!snapshot.allowedShells.includes(shell))throw new Error('当前身份无权进入此界面，请使用教师工作台');
    if(!alive)return;state.value=snapshot;
    if(!snapshot.classes.includes(form.classId))form.classId=snapshot.classes[0]||'';
    const page=await api.list(cursor);if(alive){rows.value=page.items;nextCursor.value=page.nextCursor;}
  }
  async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';try{await action();}catch(e){if(alive)error.value=e instanceof Error?e.message:'操作失败';}finally{if(alive)busy.value=false;}}
  function create(){return run(async()=>{
    const data={...form},fingerprint=JSON.stringify(data);
    if(!pending||pending.fingerprint!==fingerprint)pending={fingerprint,key:crypto.randomUUID()};
    savedRecord.value=null;
    const committed=await api.create(data,pending.key);pending=null;
    if(alive)savedRecord.value=committed;
    await refresh();
  });}
  function analyze(){return run(async()=>{const d=await api.analyze();if(alive)summary.value=`${d.count} 条记录，平均 ${d.average} 分。`;});}
  function toggle(){return run(async()=>{await api.compose(!state.value?.pythonEnabled);await refresh();});}
  onMounted(()=>run(()=>refresh()));onBeforeUnmount(()=>{alive=false;api.clear();});
  return {state,rows,error,summary,busy,form,nextCursor,savedRecord,create,analyze,toggle,reload:()=>run(()=>refresh()),next:()=>run(()=>refresh(nextCursor.value||''))};
}
