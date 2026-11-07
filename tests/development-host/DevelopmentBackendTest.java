package dev.fcp.dev;

import fcp.*;
import java.nio.file.*;
import java.util.*;
import java.net.*;
import java.net.http.*;

/** File persistence is tested here; actual JDBC behavior is separately tested in native CI. */
public final class DevelopmentBackendTest {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("fcp-dev-settings-");
        try(var settings=new DevelopmentSettings(root)){
            check(Schema.number(settings.get("t1").get("revision"))==0);
            var written=settings.put("t1",0,Map.of("theme","dark"));
            check(Schema.number(written.get("revision"))==1);
            check(Schema.map(settings.get("t2").get("values")).isEmpty());
            try{settings.put("t1",0,Map.of("theme","light"));throw new AssertionError();}catch(FcpException e){check(e.code.equals("VERSION_CONFLICT"));}
            try{settings.put("t1",1,Map.of("../bad","value"));throw new AssertionError();}catch(FcpException e){check(e.code.equals("INVALID_ARGUMENT"));}
        }
        try(var settings=new DevelopmentSettings(root)) {check(Schema.map(settings.get("t1").get("values")).equals(Map.of("theme","dark")));}
        for(String bad:List.of("jdbc:mysql://external.example:3306/test","jdbc:mysql://127.0.0.1/test","jdbc:h2:mem:test")){
            try{new DevelopmentDataSource(bad,"dev","private");throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        }
        new DevelopmentDataSource("jdbc:mysql://127.0.0.1:3306/fcp_dev?useSSL=false","dev","private");checks++;
        Path hostRoot=Files.createTempDirectory("fcp-dev-backend-");Path config=hostRoot.resolve("identity.json");
        String admin="a".repeat(40),viewer="v".repeat(40),other="o".repeat(40);
        var adminId=Json.obj("token",admin,"tenantId","t1","userId","admin","scopes",List.of("tenant:t1"),"permissions",List.of("fcp:plugin:view","fcp:plugin:manage"),"grants",List.of());
        var viewId=Json.obj("token",viewer,"tenantId","t1","userId","viewer","scopes",List.of("tenant:t1"),"permissions",List.of("fcp:plugin:view"),"grants",List.of());
        var otherId=Json.obj("token",other,"tenantId","t2","userId","other","scopes",List.of("tenant:t2"),"permissions",List.of("fcp:plugin:view","fcp:plugin:manage"),"grants",List.of());
        var configuration=Json.obj("development",true,"identities",List.of(adminId,viewId,otherId),"trustedPublishers",List.of(),"executables",Map.of());Files.writeString(config,Json.write(configuration));
        try(var host=new DevHost(hostRoot.resolve("state"),config,0)) {
            host.start();String base="http://localhost:"+host.port();
            check(request(base,"GET","/fcp/session",null,null).statusCode()==401);
            var session=request(base,"GET","/fcp/session",admin,null);check(session.statusCode()==200);
            check(Boolean.FALSE.equals(Schema.map(Schema.map(Json.parse(session.body())).get("data")).get("exchangeEnabled")));
            String input=Json.write(Map.of("expectedRevision",0,"values",Map.of("theme","dark")));
            check(request(base,"POST","/fcp/development/configuration",viewer,input).statusCode()==403);
            check(request(base,"POST","/fcp/development/configuration",admin,input).statusCode()==200);
            check(request(base,"POST","/fcp/development/configuration",admin,input).statusCode()==409);
            var separate=request(base,"GET","/fcp/development/configuration",other,null);
            check(Schema.map(Schema.map(Schema.map(Json.parse(separate.body())).get("data")).get("values")).isEmpty());
            check(request(base,"POST","/fcp/exchange/configs/roster-fixture/exports",admin,"{}").statusCode()==404);
            check(request(base,"GET","/dev/../identity.json",null,null).statusCode()==404);
            configuration.put("development",false);Files.writeString(config,Json.write(configuration));
            check(request(base,"GET","/fcp/session",admin,null).statusCode()==403);
        }
        try{new DevHost(hostRoot.resolve("forbidden"),config,0);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS "+checks+" development configuration checks; JDBC server NOT_RUN in this unit test");
    }
    private static HttpResponse<String> request(String base,String method,String path,String token,String body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path));if(token!=null)request.header("Authorization","Bearer "+token);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
