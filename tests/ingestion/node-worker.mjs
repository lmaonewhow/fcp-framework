// Real second-language provider used only by protocol interoperability regression.
import readline from 'node:readline';
const lines = readline.createInterface({ input: process.stdin });
for await (const line of lines) {
  const request = JSON.parse(line);
  const records = request.params.records.toSorted((a, b) => a.studentId.localeCompare(b.studentId, 'en'));
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id,
    result: { records, summary: { students: records.length, nodeRuntime: 1 }, pid: process.pid } }) + '\n');
}
