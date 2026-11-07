package fcp.exchange;

import fcp.*;
import fcp.control.StateJournal;
import fcp.host.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Single-instance exchange coordinator. All config and contexts originate in the host.
 * Business parsing/validation belongs to installed plugins. Journal is file in development,
 * JDBC in native hosts. No user-supplied destination, mapping, schema or task identity is trusted.
 */
public final class ExchangeService implements AutoCloseable {
    public record Config(String id,String plugin,String scope,String format,String mappingVersion,String schemaVersion,
                         Set<String> fields,String connector,String taskPrincipal) {
        public Config { fields=Set.copyOf(fields); for(String s:List.of(id,plugin,scope,format,mappingVersion,schemaVersion,connector,taskPrincipal))if(s==null||s.isBlank())throw new IllegalArgumentException("Config"); }
    }
    public interface ExecutionPort {
        /** Current enabled package digest/version; must fail if disabled. */
        String version(TrustedContext c,String plugin);
        Object call(TrustedContext c,String plugin,String operation,Map<String,Object> input,String key);
    }
    public record Page(String content,String nextCursor) {}
    public interface Connector { Page fetch(String cursor)throws IOException; }
    private final HostCapabilities host;private final ExecutionPort execution;private final StateJournal journal;
    private final Map<String,Config> configs;private final Map<String,Connector> connectors;
    private StateJournal.Snapshot snapshot;private Map<String,Object> state;
    public ExchangeService(HostCapabilities host,ExecutionPort execution,StateJournal journal,Collection<Config> configs,Map<String,Connector> connectors)throws IOException {
        this.host=host;this.execution=execution;this.journal=journal;this.connectors=Map.copyOf(connectors);Map<String,Config> cs=new HashMap<>();for(Config c:configs)if(cs.put(c.id(),c)!=null)throw new IllegalArgumentException("Duplicate config");this.configs=Map.copyOf(cs);snapshot=journal.load();state=snapshot.json()==null?new LinkedHashMap<>():new LinkedHashMap<>(Schema.map(Json.parse(snapshot.json())));
    }
    private Config config(String id){Config c=configs.get(id);if(c==null)throw fail("CONFIG_NOT_FOUND");return c;}
    private long authorize(TrustedContext context,Config config,String operation){
        host.require(context,new HostCapabilities.Grant(config.plugin(),"exchange",operation,config.scope()));
        var decision=host.authorize(context,new HostCapabilities.Grant(config.plugin(),"exchange",operation,config.scope()));
        if(decision==null||!decision.allowed())throw fail("PERMISSION_DENIED");return decision.currentRevision();
    }
    public synchronized Map<String,Object> preview(TrustedContext c,String configId,String source,InputStream input)throws IOException {
        if(c.principal().kind()!=TrustedContext.Kind.USER)throw fail("USER_REQUIRED");
        return previewContent(c,config(configId),source,read(input));
    }
    /** Manual API preview uses only a configured connector, never a client URL. */
    public synchronized Map<String,Object> previewConnector(TrustedContext c,String configId,String cursor)throws IOException {
        if(c.principal().kind()!=TrustedContext.Kind.USER)throw fail("USER_REQUIRED");
        Config cfg=config(configId);authorize(c,cfg,"import");
        if(cursor==null||cursor.length()>256)throw fail("CURSOR_LIMIT");
        Connector connector=connectors.get(cfg.connector());if(connector==null)throw fail("CONNECTOR_NOT_CONFIGURED");
        Page page=connector.fetch(cursor);var result=new LinkedHashMap<>(previewContent(c,cfg,"connector:"+cfg.connector()+":"+cursor,page.content()));result.put("nextCursor",page.nextCursor());return result;
    }
    private Map<String,Object> previewContent(TrustedContext c,Config cfg,String source,String content)throws IOException {
        if(source==null||source.isBlank()||source.length()>256)throw fail("INVALID_SOURCE");
        long auth=authorize(c,cfg,"import");String version=execution.version(c,cfg.plugin());
        Map<String,Object> input=payload(cfg,content);
        Map<String,Object> result=Schema.map(execution.call(c,cfg.plugin(),"exchange.preview",input,null));
        Map<String,Object> plan=new LinkedHashMap<>();plan.put("owner",owner(c));plan.put("config",cfg.id());plan.put("source",source);plan.put("content",content);plan.put("binding",binding(cfg));plan.put("version",version);plan.put("authorizationRevision",auth);plan.put("preview",result);plan.put("expires",host.clock().instant().plusSeconds(600).toString());
        String digest=hash(Json.write(plan)),id=UUID.randomUUID().toString();plan.put("digest",digest);put("preview:"+id,plan);
        return Map.of("previewId",id,"digest",digest,"source",source,"result",result,"batchSemantics","atomic-class-snapshot-reject-conflicts");
    }
    public synchronized Object confirm(TrustedContext c,String id,String digest,String key)throws IOException {
        Map<String,Object> plan=entry("preview:"+id);Config cfg=config(Schema.str(plan.get("config")));
        long auth=authorize(c,cfg,"import");
        if(!owner(c).equals(plan.get("owner")))throw fail("PERMISSION_DENIED");
        if(!Objects.equals(digest,plan.get("digest"))||!binding(cfg).equals(plan.get("binding"))||!execution.version(c,cfg.plugin()).equals(plan.get("version"))||auth!=Schema.number(plan.get("authorizationRevision")))throw fail("STALE_PREVIEW");
        if(key==null||key.isBlank()||key.length()>128)throw fail("IDEMPOTENCY_REQUIRED");
        String receipt="receipt:"+hash(owner(c)+"/"+cfg.id()+"/"+key);Object prior=state.get(receipt);
        if(prior!=null){var r=Schema.map(prior);if(!digest.equals(r.get("digest")))throw fail("IDEMPOTENCY_CONFLICT");return r.get("result");}
        if(!host.clock().instant().isBefore(java.time.Instant.parse(Schema.str(plan.get("expires")))))throw fail("PREVIEW_EXPIRED");
        var preview=Schema.map(plan.get("preview"));if(!Boolean.TRUE.equals(preview.get("valid")))throw fail("VALIDATION_FAILED");
        var input=payload(cfg,Schema.str(plan.get("content")));input.put("expectedRevision",preview.get("revision"));input.put("receiptKey",hash(receipt));
        Object result=execution.call(c,cfg.plugin(),"exchange.commit",input,hash(receipt));
        if(!Boolean.TRUE.equals(Schema.map(result).get("committed")))throw fail("CONFLICT");
        put(receipt,Map.of("digest",digest,"result",result));audit(c,cfg,"import","COMPLETE");return result;
    }
    /** Called by a host scheduler with freshly authenticated TASK context, never a creator's USER. */
    public synchronized Object runTask(TrustedContext c,String configId)throws IOException {
        Config cfg=config(configId);
        if(c.principal().kind()!=TrustedContext.Kind.TASK||!c.principal().id().equals(cfg.taskPrincipal()))throw fail("TASK_IDENTITY_REQUIRED");
        authorize(c,cfg,"task");authorize(c,cfg,"import");
        // A disabled/unhealthy package must not fetch another scheduled source page.
        execution.version(c,cfg.plugin());
        String task="task:"+owner(c)+":"+cfg.id();Map<String,Object> progress=state.containsKey(task)?entry(task):Map.of("cursor","");
        if(Boolean.TRUE.equals(progress.get("complete")))return progress;
        String cursor=Schema.str(progress.get("cursor"));Connector connector=connectors.get(cfg.connector());if(connector==null)throw fail("CONNECTOR_NOT_CONFIGURED");
        Map<String,Object> p;
        if(progress.containsKey("previewId")){p=progress;}else{
            Page page=connector.fetch(cursor);if(page.nextCursor()!=null&&page.nextCursor().equals(cursor))throw fail("CURSOR_STALLED");
            p=new LinkedHashMap<>(previewContent(c,cfg,"connector:"+cfg.connector()+":"+cursor,page.content()));p.put("cursor",cursor);p.put("nextCursor",page.nextCursor()==null?"":page.nextCursor());p.put("complete",page.nextCursor()==null);put(task,p);
        }
        authorize(c,cfg,"task");Object receipt=confirm(c,Schema.str(p.get("previewId")),Schema.str(p.get("digest")),hash(task+":"+cursor));
        // Advance only after durable successful receipt. Failed/unknown batches retain the same cursor.
        put(task,Map.of("cursor",p.get("nextCursor"),"complete",p.get("complete"),"lastReceipt",receipt));return receipt;
    }
    public synchronized Map<String,Object> export(TrustedContext c,String configId,Set<String> fields)throws IOException {
        Config cfg=config(configId);authorize(c,cfg,"export");if(fields.isEmpty()||!cfg.fields().containsAll(fields))throw fail("FIELD_DENIED");
        Object raw=execution.call(c,cfg.plugin(),"exchange.read",Map.of("dataScope",cfg.scope()),null);
        List<Object> filtered=new ArrayList<>();for(Object row:Schema.list(Schema.map(raw).get("rows"))){Map<String,Object> out=new TreeMap<>();for(String f:fields)out.put(f,Schema.map(row).get(f));filtered.add(out);}
        String id=UUID.randomUUID().toString();put("download:"+id,Map.of("owner",owner(c),"config",cfg.id(),"binding",binding(cfg),"fields",new ArrayList<>(new TreeSet<>(fields)),"rows",filtered,"expires",host.clock().instant().plusSeconds(300).toString()));audit(c,cfg,"export","READY");return Map.of("handle",id,"format","csv","rows",filtered.size());
    }
    public synchronized byte[] download(TrustedContext c,String handle){
        var d=entry("download:"+handle);Config cfg=config(Schema.str(d.get("config")));authorize(c,cfg,"export");
        if(!owner(c).equals(d.get("owner"))||!binding(cfg).equals(d.get("binding"))||!host.clock().instant().isBefore(java.time.Instant.parse(Schema.str(d.get("expires")))))throw fail("DOWNLOAD_DENIED");
        List<String> fields=Schema.list(d.get("fields")).stream().map(Schema::str).toList();StringBuilder out=new StringBuilder(String.join(",",fields)).append("\r\n");
        for(Object row:Schema.list(d.get("rows"))){var m=Schema.map(row);out.append(fields.stream().map(f->csv(m.get(f))).collect(java.util.stream.Collectors.joining(","))).append("\r\n");}audit(c,cfg,"download","COMPLETE");return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String csv(Object o){String s=o==null?"":o.toString();if(s.matches("^[=+@\\-\\t\\r].*"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
    private Map<String,Object> payload(Config c,String content){return new LinkedHashMap<>(Map.of("format",c.format(),"dataScope",c.scope(),"content",content));}
    private static String binding(Config c){return hash(Json.write(List.of(c.id(),c.plugin(),c.scope(),c.format(),c.mappingVersion(),c.schemaVersion(),new TreeSet<>(c.fields()),c.connector(),c.taskPrincipal())));}
    private static String owner(TrustedContext c){return Json.write(List.of(c.tenantId(),c.principal().kind().name(),c.principal().id()));}
    private Map<String,Object> entry(String key){Object o=state.get(key);if(o==null)throw fail("NOT_FOUND");return Schema.map(o);}
    private void put(String key,Object value)throws IOException{Map<String,Object> next=new LinkedHashMap<>(state);next.put(key,value);snapshot=journal.compareAndSet(snapshot.revision(),Json.write(next));state=next;}
    private void audit(TrustedContext c,Config cfg,String action,String outcome){host.audit(new HostCapabilities.Audit(c.requestId(),c.traceId(),c.tenantId(),c.principal().id(),cfg.plugin(),action,outcome,host.clock().instant()));}
    static String read(InputStream in)throws IOException{byte[] bytes=in.readNBytes(1024*1024+1);if(bytes.length>1024*1024)throw fail("SOURCE_LIMIT");try{return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException e){throw fail("INVALID_ENCODING");}}
    static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    static FcpException fail(String code){return new FcpException(code,"Exchange request rejected");}
    public void close()throws IOException{journal.close();}
}
