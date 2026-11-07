import fcp.*;
import java.util.*;
import static fcp.Json.obj;

/** Actual Engine publication; in-memory provider fixture, not process/HTTP acceptance. */
public class CatalogPublicationTest {
    static Map<String,Object> contract(String id) {
        return obj("id",id,"operations",obj("read",obj("input",obj("type","object"),"output",obj("type","object"))));
    }
    static Map<String,Object> module(String id, String contract) {
        return obj("protocol","fcp/0.3","kind","module","id",id,"version","1.0.0",
            "provides",obj("api",contract),"runtime",obj("provider","test"));
    }
    static Map<String,Object> plan(String module) {
        return obj("protocol","fcp/0.3","kind","composition","id","test.plan",
            "modules",List.of(module),"exports",obj("service",module+"/api"));
    }
    public static void main(String[] args) {
        var registry = new Registry();
        registry.factories.put("test", (m,s)-> (c,e,o,i)->obj("ok",true));
        try(var engine = new Engine(registry, null)) {
            var docs = List.of(obj("kind","contract","document",contract("test.v1")),
                               obj("kind","module","document",module("test.plugin","test.v1")));
            engine.publishCatalog("tenant-a",docs,plan("test.plugin"),0);
            if (!obj("ok",true).equals(engine.invoke("tenant-a","service","read",Map.of(),Map.of(),1L)))
                throw new AssertionError("New catalog unavailable");
            try {
                engine.publishCatalog("tenant-a",List.of(obj("kind","contract","document",contract("unused.v1"))),plan("missing.plugin"),1);
                throw new AssertionError("Invalid plan accepted");
            } catch(FcpException expected) { }
            if(registry.contracts.containsKey("unused.v1")) throw new AssertionError("Failed registration leaked");
            engine.invoke("tenant-a","service","read",Map.of(),Map.of(),1L);
            try {
                engine.publishCatalog("tenant-a",List.of(obj("kind","contract","document",contract("stale.v1"))),plan("test.plugin"),0);
                throw new AssertionError("Stale publication accepted");
            } catch(FcpException expected) { }
            if(registry.contracts.containsKey("stale.v1")) throw new AssertionError("Stale registration leaked");
        }
        System.out.println("Catalog publication tests PASS");
    }
}
