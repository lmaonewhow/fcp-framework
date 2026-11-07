import readline from 'node:readline';
const stream=readline.createInterface({input:process.stdin,crlfDelay:Infinity});
const lines=stream[Symbol.asyncIterator]();
const send=value=>process.stdout.write(JSON.stringify(value)+'\n');
for (;;) {
  const next=await lines.next();if(next.done)break;
  const request=JSON.parse(next.value),params=request.params,method=request.method;let result;
  if(method==='handshake')result=params;
  else if(['ready','health','drain','cancel','shutdown'].includes(method))result={ok:true};
  else if(method==='invoke') {
    if(params.input.dependency) {
      send({jsonrpc:'2.0',id:'native-callback',method:'fcp.call',params:{dependency:'echo',operation:'invoke',input:{message:params.input.message??''}}});
      const reply=JSON.parse((await lines.next()).value);
      if(reply.error) {send({jsonrpc:'2.0',id:request.id,error:{code:-32000,message:'Approved dependency was denied',data:{code:'BUSINESS_RULE'}}});continue;}
      result={...reply.result,callerLanguage:'node'};
    } else result={language:'node',message:params.input.message??'',tenantId:params.context.tenantId,principalKind:params.context.principal.kind,principalId:params.context.principal.id,pluginPid:process.pid};
  } else throw Error('Unsupported native acceptance method');
  send({jsonrpc:'2.0',id:request.id,result});if(method==='shutdown')break;
}
