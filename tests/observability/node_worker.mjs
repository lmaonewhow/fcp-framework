import readline from 'node:readline';
import { withTrace, currentTrace, outboundHeaders } from '../../runtime/observability/fcp-trace.mjs';
for await (const line of readline.createInterface({ input: process.stdin })) {
 const request = JSON.parse(line);
 const result = await withTrace(request.traceparent, async () => { await Promise.resolve(); return { ...currentTrace(), downstream: outboundHeaders().traceparent }; });
 process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:request.id,result})+'\n');
 if (currentTrace() !== undefined) throw new Error('trace leaked');
}
