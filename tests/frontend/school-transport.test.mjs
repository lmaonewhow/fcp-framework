import {expect,it,vi} from 'vitest';
import {transport} from '../../integration/plus-ui/src/views/fcp/school/transport';
const probes=vi.hoisted(()=>({request:vi.fn()}));
vi.mock('@/utils/request',()=>({default:probes.request}));
it('retains a native FCP reason when HTTP 400 rejects before envelope handling',async()=>{
 probes.request.mockRejectedValueOnce({response:{status:400,data:{code:400,msg:'Select a smaller reporting window before analysis',data:{fcpCode:'RESULT_LIMIT'}}}});
 await expect(transport('/fcp/school/evaluation/analyze','POST',{})).rejects.toThrow('Select a smaller reporting window before analysis');
});
it('preserves unrelated transport failures',async()=>{
 const failure=new Error('Connection unavailable');probes.request.mockRejectedValueOnce(failure);
 await expect(transport('/fcp/school/bootstrap','GET')).rejects.toBe(failure);
});
