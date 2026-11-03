/** Pass the existing host request wrapper so auth, encryption, client ID and refresh remain intact. */
export type Transport = <T>(url: string, method: 'GET' | 'POST', data?: unknown) => Promise<T>;
export interface Snapshot {
  protocol: string; instance: string; generation: number;
  caller: {scope: string; subject: string}; shell: 'admin'|'teacher';
  allowedShells: string[]; operations: string[]; classes: string[]; pythonEnabled: boolean;
}
export interface RecordRow {id: string; body: {student: string; classId: string; indicator: string; score: number; comment: string}}
export interface EvaluationData {student: string; classId: string; indicator: string; score: number; comment: string}
export interface Statistics {count:number; average:number; minimum:number; maximum:number; engine:string; pid:number}
export function createSchoolBridge(transport:Transport) {
  let current:Snapshot|null=null;
  const prefix='/fcp/school';
  function envelope(input:unknown){
    if(!current)throw new Error('Load the authenticated host snapshot first');
    return {input,generation:current.generation,instance:current.instance,expectedCaller:{...current.caller}};
  }
  return {
    get snapshot(){return current;},
    async refresh(){current=await transport<Snapshot>(prefix+'/bootstrap','GET');return current;},
    list(cursor=''){return transport<{items:RecordRow[];nextCursor:string|null}>(prefix+'/evaluation/list','POST',envelope({limit:25,...(cursor?{cursor}:{})}));},
    create(data:EvaluationData,requestKey:string){return transport<RecordRow>(prefix+'/evaluation/create','POST',envelope({data,requestKey}));},
    analyze(){return transport<Statistics>(prefix+'/evaluation/analyze','POST',envelope({}));},
    async compose(enabled:boolean){current=await transport<Snapshot>(prefix+'/composition','POST',envelope({enabled}));return current;},
    clear(){current=null;}
  };
}
