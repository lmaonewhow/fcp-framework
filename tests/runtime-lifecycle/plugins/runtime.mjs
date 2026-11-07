import readline from 'node:readline';
let count=0;
const lines=readline.createInterface({input:process.stdin,crlfDelay:Infinity});
for await (const line of lines) {
  const r=JSON.parse(line),p=r.params,m=r.method;let result;
  if(m==='handshake') result=p;
  else if(['ready','health','drain','cancel','shutdown'].includes(m)) result={ok:true};
  else if(m==='invoke') {
    count++;
    if(p.input.crash)process.exit(7);
    if(p.input.sleep)await new Promise(resolve=>setTimeout(resolve,p.input.sleep*1000));
    result={input:p.input,tenant:p.context.tenantId,principal:p.context.principal,count,secretPresent:'FCP_TEST_SECRET' in process.env,pid:process.pid};
  } else throw Error('unsupported method');
  process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:r.id,result})+'\n');
  if(m==='shutdown')break;
}
