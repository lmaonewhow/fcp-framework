import readline from 'node:readline';
import { withTrace, currentTrace, outboundHeaders } from '../../runtime/observability/fcp-trace.mjs';
const secret = 'synthetic-payload-query-token-principal-error-stderr-canary';
for await (const line of readline.createInterface({ input: process.stdin })) {
 const request = JSON.parse(line), mode = request.params.mode;
 if (mode === 'timeout') await new Promise(resolve => setTimeout(resolve, 2000));
 const result = await withTrace(request.traceparent, async () => { await Promise.resolve(); return { ...currentTrace(), downstream: outboundHeaders().traceparent }; });
 let response = {jsonrpc:'2.0',id:request.id,result};
 if (mode === 'error') {
  process.stderr.write(secret+'\n');
  response = {jsonrpc:'2.0',id:request.id,error:{code:-32000,message:secret,data:{code:request.params.errorCode}}};
 } else if (mode === 'protocol-error') response.id = 'wrong-id';
 else if (mode === 'business-unknown') response.result = {ok:false,code:500,token:secret};
 process.stdout.write(JSON.stringify(response)+'\n');
 if (currentTrace() !== undefined) throw new Error('trace leaked');
}
