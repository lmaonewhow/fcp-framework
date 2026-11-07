package org.dromara.fcp.plugin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import dev.fcp.dual.HostResponse;
import fcp.*;
import fcp.host.*;
import fcp.runtime.VerifiedPackageRepository;
import fcp.storage.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static fcp.Schema.*;

/** Platform-only plan/apply for already verified immutable packages. No SQL, schema definitions,
 * database credentials, tenant or principal can be supplied by HTTP. DDL is checkpointed and
 * synchronous; HTTP disconnect does not undo it. Receipts expose uncertainty for reconciliation.
 */
@RestController
@ConditionalOnProperty(name="fcp.runtime.enabled",havingValue="true")
@RequestMapping(value="/fcp",produces=MediaType.APPLICATION_JSON_VALUE)
public final class FcpStorageDeploymentController {
    private record Pending(String plugin,String version,String packageDigest,StorageDeploymentService.Plan plan,String digest,String owner,Instant expires){}
    private final NativePluginHost identity;private final VerifiedPackageRepository packages;
    private final FcpStorageDeploymentConfiguration.DeploymentAccess access;private final DataSource runtime;
    private final Map<String,Pending> plans=new HashMap<>();
    public FcpStorageDeploymentController(NativePluginHost identity,VerifiedPackageRepository packages,FcpStorageDeploymentConfiguration.DeploymentAccess access,DataSource runtime){this.identity=identity;this.packages=packages;this.access=access;this.runtime=runtime;}
    @ModelAttribute public void requireExplicitToken(HttpServletRequest request){
        String header=request.getHeader("Authorization");if(header==null||header.isBlank())throw new FcpException("UNAUTHENTICATED","Explicit native authorization header required");
        String token=header.startsWith("Bearer ")?header.substring(7):header,actual=StpUtil.getTokenValue();
        if(actual==null||!java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8)))throw new FcpException("UNAUTHENTICATED","Native authorization header mismatch");
    }
    @GetMapping("/storage/deployment") @SaCheckPermission("fcp:plugin:install")
    public ResponseEntity<String> availability(){var c=identity.current();require(c,"control");return ok(Json.obj("configured",access.configured(),"mode",access.configured()?"EXPLICIT_DEPLOYMENT_IDENTITY":"DISABLED"));}
    @PostMapping(value="/plugins/{plugin}/storage/plans",consumes=MediaType.APPLICATION_JSON_VALUE) @SaCheckPermission("fcp:plugin:install")
    public synchronized ResponseEntity<String> plan(@PathVariable String plugin,@RequestBody String text)throws IOException{
        var c=identity.current();require(c,plugin);var service=access.require();var body=body(text,Set.of("version","packageDigest"));String version=str(body.get("version")),packageDigest=str(body.get("packageDigest"));
        var pkg=packages.resolve(plugin,version,packageDigest);if(pkg.manifest().storage().isEmpty())throw new FcpException("INVALID_ARGUMENT","Package has no storage declaration");
        var plan=service.plan(plugin,pkg.manifest().storage());String digest=JdbcPluginStorage.hash(Json.write(List.of(plugin,version,packageDigest,plan.digest(),owner(c))));
        plans.entrySet().removeIf(e->!identity.clock().instant().isBefore(e.getValue().expires()));if(plans.size()>=256)throw new FcpException("BUSY","Storage plan limit reached");
        String id="schema-plan-"+UUID.randomUUID();plans.put(id,new Pending(plugin,version,packageDigest,plan,digest,owner(c),identity.clock().instant().plusSeconds(300)));
        List<Object> changes=new ArrayList<>();for(var table:plan.tables())for(var step:table.steps())changes.add(Json.obj("structure",table.structure(),"step",step.version(),"action",step.kind().name(),"field",step.field(),"type",step.type()==null?null:step.type().name(),"indexFields",step.indexFields()));
        identity.audit(new HostCapabilities.Audit(c.requestId(),c.traceId(),c.tenantId(),c.principal().id(),plugin,"storage.plan","CREATED",identity.clock().instant()));
        return ok(Json.obj("planId",id,"digest",digest,"plugin",plugin,"version",version,"packageDigest",packageDigest,"expectedSchemaVersion",plan.expectedVersion(),"schemaVersion",plan.declaration().version(),"changes",changes,"expiresAt",plans.get(id).expires().toString(),"ddlTransactional",false));
    }
    @PostMapping(value="/storage/plans/{planId}/apply",consumes=MediaType.APPLICATION_JSON_VALUE) @SaCheckPermission("fcp:plugin:install")
    public synchronized ResponseEntity<String> apply(@PathVariable String planId,@RequestBody String text)throws IOException{
        var c=identity.current();require(c,"control");var service=access.require();var body=body(text,Set.of("digest","idempotencyKey"));String digest=str(body.get("digest")),key=str(body.get("idempotencyKey"));
        if(key.isBlank()||key.length()>128||!digest.matches("[a-f0-9]{64}"))throw new FcpException("INVALID_ARGUMENT","Invalid confirmation");
        String operation="schema."+JdbcPluginStorage.hash(owner(c)+"\n"+key).substring(0,48);
        try(var journal=new JdbcStateJournal(runtime,operation)){
            var snapshot=journal.load();Map<String,Object> saved=snapshot.json()==null?null:map(Json.parse(snapshot.json()));
            if(saved!=null){if(!owner(c).equals(saved.get("owner"))||!digest.equals(saved.get("digest")))throw new FcpException("IDEMPOTENCY_CONFLICT","Storage confirmation changed");if("COMPLETE".equals(saved.get("phase")))return ok(view(saved));if("RUNNING".equals(saved.get("phase")))return outcomeUnknown(operation);}
            Pending p=plans.get(planId);if(p==null||!p.owner().equals(owner(c))||!identity.clock().instant().isBefore(p.expires()))throw new FcpException("PLAN_EXPIRED","Generate a new storage plan");
            if(!p.digest().equals(digest))throw new FcpException("CONFIRMATION_REQUIRED","Confirm the exact storage plan");require(c,p.plugin());
            var pkg=packages.resolve(p.plugin(),p.version(),p.packageDigest());if(!StorageDeclaration.parse(pkg.manifest().storage()).digest().equals(p.plan().declaration().digest()))throw new FcpException("PACKAGE_INTEGRITY","Package declaration changed");
            Map<String,Object> receipt=Json.obj("operationId",operation,"owner",owner(c),"digest",digest,"plugin",p.plugin(),"version",p.version(),"packageDigest",p.packageDigest(),"schemaVersion",p.plan().declaration().version(),"phase","RUNNING","outcome","PENDING");
            var intent=journal.compareAndSet(snapshot.revision(),Json.write(receipt));
            try{service.apply(c,p.plan(),p.plan().digest());receipt.put("phase","COMPLETE");receipt.put("outcome","APPLIED");journal.compareAndSet(intent.revision(),Json.write(receipt));return ok(view(receipt));}
            catch(RuntimeException|IOException failure){receipt.put("phase","RECONCILIATION_REQUIRED");receipt.put("outcome","UNKNOWN");try{journal.compareAndSet(intent.revision(),Json.write(receipt));}catch(IOException ignored){}return outcomeUnknown(operation);}
        }
    }
    @GetMapping("/storage/operations/{operation}") @SaCheckPermission("fcp:plugin:install")
    public ResponseEntity<String> operation(@PathVariable String operation)throws IOException{
        var c=identity.current();require(c,"control");if(!operation.matches("schema\\.[a-f0-9]{48}"))throw new FcpException("NOT_FOUND","Unknown storage operation");
        try(var journal=new JdbcStateJournal(runtime,operation)){var saved=journal.load();if(saved.json()==null)throw new FcpException("NOT_FOUND","Unknown storage operation");var result=map(Json.parse(saved.json()));if(!owner(c).equals(result.get("owner")))throw new FcpException("NOT_FOUND","Unknown storage operation");return ok(view(result));}
    }
    private void require(TrustedContext c,String plugin){StorageSchema.pluginIdentifier(plugin);identity.require(c,new HostCapabilities.Grant(plugin,"storage.schema","migrate","tenant:"+c.tenantId()));}
    private static String owner(TrustedContext c){return c.tenantId()+":"+c.principal().kind()+":"+c.principal().id();}
    private static Map<String,Object> view(Map<String,Object> receipt){var result=new LinkedHashMap<>(receipt);result.remove("owner");return result;}
    private static ResponseEntity<String> outcomeUnknown(String operation){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(Json.write(Json.obj("code",503,"msg","Storage deployment outcome uncertain; inspect the operation receipt and prepare a reviewed reconciliation plan","data",Json.obj("fcpCode","OUTCOME_UNKNOWN","operationId",operation))));}
    private static ResponseEntity<String> ok(Object data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(HostResponse.ok(data));}
    private static Map<String,Object> body(String text,Set<String> allowed){if(text.length()>16384)throw new FcpException("INVALID_ARGUMENT","Request too large");var body=map(Json.parse(text));if(!allowed.equals(body.keySet()))throw new FcpException("INVALID_ARGUMENT","Missing or unknown request fields");return body;}
}
