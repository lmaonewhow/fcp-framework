package fcp;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static fcp.Schema.*;

/** Trusted JSON-lines embedding console; not a publicly authenticated HTTP service. */
public final class Main {
    public static void main(String[] arguments)throws Exception{
        System.setOut(new PrintStream(System.out,true,StandardCharsets.UTF_8));Path root=Path.of(arguments.length>0?arguments[0]:".").toAbsolutePath().normalize();boolean secure=Arrays.asList(arguments).contains("--protected");
        try(Engine engine=Providers.bootstrap(root,secure,(ctx,input)->"admin".equals(ctx.attributes.get("role")));BufferedReader reader=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){
            String line;while((line=reader.readLine())!=null){Object id=null;Map<String,Object> response;
                try{
                    if(line.getBytes(StandardCharsets.UTF_8).length>1048576)throw new FcpException("FRAME_LIMIT","Input frame too large");
                    Map<String,Object> request=map(Json.parse(line));id=request.get("id");Map<String,Object> p=obj(request,"params");
                    Object result=switch(str(request.get("method"))){
                        case "host.publish"->engine.publish(str(p.get("scope")),p.get("composition"),number(p.getOrDefault("expected",0)));
                        case "host.describe"->engine.describe(str(p.get("scope")));
                        case "host.invoke"->engine.invoke(str(p.get("scope")),str(p.get("capability")),str(p.get("operation")),obj(p,"input"),obj(p,"attributes"),p.containsKey("expected")?number(p.get("expected")):null,(String)p.get("instance"));
                        default->throw new FcpException("METHOD_MISSING","Unknown host method");};
                    response=Json.obj("id",id,"result",result);
                }catch(FcpException error){response=Json.obj("id",id,"error",Json.obj("code",error.code,"message",error.getMessage()));}
                catch(Exception error){response=Json.obj("id",id,"error",Json.obj("code","INVALID_JSON","message",String.valueOf(error.getMessage())));}
                System.out.println(Json.write(response));System.out.flush();
            }
        }
    }
}
