package dev.fcp.adapter;
import fcp.Engine;
import fcp.FcpException;
import java.util.Map;

/** Existing services retain database ownership, row filtering, transactions and idempotency. */
public final class RecordServiceProvider implements Engine.Provider {
    public interface HostService {
        Object list(String scope,String subject,Map<String,Object> input);
        Object putWithinTransaction(String scope,String subject,Map<String,Object> input);
    }
    private final HostService service;
    public RecordServiceProvider(HostService service){this.service=service;}
    public Object invoke(Engine.Context context,String export,String operation,Map<String,Object> input){
        String subject=String.valueOf(context.attributes.get("subject"));
        if(operation.equals("list"))return service.list(context.scope(),subject,input);
        if(operation.equals("put"))return context.atomic(()->service.putWithinTransaction(context.scope(),subject,input));
        throw new FcpException("OPERATION_MISSING",operation);
    }
}
