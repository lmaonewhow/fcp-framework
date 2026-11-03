import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import {defineComponent} from 'vue';
import {useSchool} from '../../integration/plus-ui/src/views/fcp/school/useSchool';
const probes=vi.hoisted(()=>({transport:vi.fn()}));
vi.mock('../../integration/plus-ui/src/views/fcp/school/transport',()=>({transport:probes.transport}));
const snapshot={protocol:'fcp/0.3',instance:'test-instance',generation:1,caller:{scope:'tenant-test',subject:'1'},shell:'admin',allowedShells:['admin','teacher'],operations:['list','create'],classes:['class-a'],pythonEnabled:false};
const firstPage=Array.from({length:25},(_,i)=>({id:String(i).padStart(3,'0'),body:{student:'existing-'+i,classId:'class-a',indicator:'test',score:80,comment:''}}));
const committed={id:'fff-new-row',body:{student:'just-saved',classId:'class-a',indicator:'test',score:90,comment:''}};
let wrapper,school;
beforeEach(()=>{
 probes.transport.mockReset().mockImplementation(async path=>{
  if(path.endsWith('/bootstrap'))return snapshot;
  if(path.endsWith('/list'))return {items:firstPage,nextCursor:'024'};
  if(path.endsWith('/create'))return committed;
  throw new Error('Unexpected path');
 });
 wrapper=mount(defineComponent({setup(){school=useSchool('admin');return ()=>null;}}));
});
afterEach(()=>wrapper.unmount());
it('shows the committed row even when UUID pagination excludes it from the first page',async()=>{
 await flushPromises();school.form.student='just-saved';await school.create();
 expect(school.savedRecord.value).toEqual(committed);
 expect(school.rows.value).toEqual(firstPage);
 expect(school.rows.value.some(row=>row.id===committed.id)).toBe(false);
 expect(school.nextCursor.value).toBe('024');
});
it('retains a successful commit confirmation if the following refresh fails',async()=>{
 await flushPromises();
 probes.transport.mockImplementation(async path=>{
  if(path.endsWith('/create'))return committed;
  throw new Error('Refresh unavailable');
 });
 await school.create();
 expect(school.savedRecord.value).toEqual(committed);
 expect(school.error.value).toBe('Refresh unavailable');
});
