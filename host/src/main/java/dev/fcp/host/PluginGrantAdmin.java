package dev.fcp.host;

import fcp.*;
import fcp.control.LifecycleCoordinator;
import fcp.host.*;
import fcp.plugin.PluginManifest;
import fcp.runtime.VerifiedPackageRepository;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static fcp.Schema.*;

/** Explicit platform grant plans; never accessible through plugin capability callbacks. */
public final class PluginGrantAdmin {
    private record Pending(String plugin,String tenant,String packageDigest,long expectedRevision,List<Map<String,Object>> changes,
                           List<Object> previous,String digest,String owner,Instant expires) { }
    private final DataSource source;private final HostCapabilities host;private final LifecycleCoordinator lifecycle;
    private final VerifiedPackageRepository packages;private final Map<String,Pending> pending=new HashMap<>();
    public PluginGrantAdmin(DataSource source,HostCapabilities host,LifecycleCoordinator lifecycle,VerifiedPackageRepository packages){this.source=source;this.host=host;this.lifecycle=lifecycle;this.packages=packages;}
    private void require(TrustedContext c,String plugin){host.require(c,new HostCapabilities.Grant(plugin,"plugin.lifecycle","install","tenant:"+c.tenantId()));}
    private PluginManifest manifest(TrustedContext c,String plugin){
        var metadata=lifecycle.installedMetadata(c,plugin);if(!"INSTALLED".equals(metadata.get("state")))throw new FcpException("PLUGIN_DISABLED","Package not installed");
        try{return packages.resolve(plugin,str(metadata.get("version")),str(metadata.get("digest"))).manifest();}catch(java.io.IOException e){throw new FcpException("PACKAGE_INTEGRITY","Package revalidation failed",e);}
    }
    public Map<String,Object> options(TrustedContext c,String plugin,String tenant){
        require(c,plugin);tenant(tenant);var m=manifest(c,plugin);Map<String,Object> capabilities=new TreeMap<>();
        for(var cap:all(m))capabilities.put(cap.id(),Json.obj("capabilityId",cap.id(),"permission","fcp:plugin:"+cap.permission(),"sideEffects",cap.sideEffects()));
        try(Connection db=source.getConnection()){return Json.obj("targetTenant",tenant,"revision",lifecycle.revision(),"capabilities",new ArrayList<>(capabilities.values()),"currentGrants",rows(db,tenant,plugin));}
        catch(SQLException e){throw new FcpException("DATA_ACCESS","Unable to read grant options",e);}
    }
    public synchronized Map<String,Object> plan(TrustedContext c,String plugin,String tenant,long expected,List<Map<String,Object>> selections){
        require(c,plugin);tenant(tenant);if(expected!=lifecycle.revision())throw new FcpException("VERSION_CONFLICT","Installation state changed");
        var metadata=lifecycle.installedMetadata(c,plugin);var m=manifest(c,plugin);Map<String,PluginManifest.Capability> declared=new HashMap<>();for(var cap:all(m))declared.put(cap.id(),cap);
        if(selections.isEmpty()||selections.size()>128)throw new FcpException("INVALID_ARGUMENT","Select 1 to 128 explicit grants");
        List<Map<String,Object>> changes=new ArrayList<>();Map<String,Map<String,Object>> unique=new HashMap<>();
        for(var selection:selections){if(!Set.of("capabilityId","dataScope","allow").containsAll(selection.keySet()))throw new FcpException("INVALID_ARGUMENT","Unknown grant field");
            String capability=str(selection.get("capabilityId")),scope=str(selection.get("dataScope"));var cap=declared.get(capability);if(cap==null)throw new FcpException("INVALID_ARGUMENT","Capability not declared by package");
            if(!scope.equals("tenant:"+tenant)&&!scope.matches("class:[A-Za-z0-9_.-]{1,100}"))throw new FcpException("INVALID_ARGUMENT","Invalid explicit data scope");
            String target=cap.id(),operation="invoke";if(target.startsWith("host.storage.")){operation=switch(target.substring("host.storage.".length())){case "get","list"->"read";case "put"->"write";case "delete"->"delete";case "restore"->"restore";default->throw new FcpException("INVALID_ARGUMENT","Unknown storage capability");};target="storage";}
            Object allow=selection.getOrDefault("allow",Boolean.TRUE);if(!(allow instanceof Boolean))throw new FcpException("INVALID_ARGUMENT","Grant allow must be boolean");
            var change=Json.obj("capabilityId",target,"operation",operation,"permission","fcp:plugin:"+cap.permission(),"dataScope",scope,"allow",allow);
            var prior=unique.putIfAbsent(target+"/"+operation+"/"+scope,change);
            if(prior!=null){if(!prior.equals(change))throw new FcpException("INVALID_ARGUMENT","Conflicting grant selections");continue;}
            changes.add(change);
        }
        changes.sort(Comparator.comparing(Json::write));
        try(Connection db=source.getConnection()){
            ensureTenant(db,tenant);List<Object> previous=rows(db,tenant,plugin);String digest=hash(Json.write(List.of(plugin,tenant,metadata.get("digest"),expected,changes,previous)));
            pending.entrySet().removeIf(e->host.clock().instant().isAfter(e.getValue().expires()));if(pending.size()>=1024)throw new FcpException("BUSY","Grant plan limit");
            String id="grant-"+UUID.randomUUID();pending.put(id,new Pending(plugin,tenant,str(metadata.get("digest")),expected,List.copyOf(changes),previous,digest,owner(c),host.clock().instant().plusSeconds(300)));
            return Json.obj("planId",id,"digest",digest,"affectedPlugins",List.of(plugin),"targetTenant",tenant,"changes",changes,"expectedRevision",expected);
        }catch(SQLException e){throw new FcpException("DATA_ACCESS","Unable to plan grants",e);}
    }
    public synchronized Map<String,Object> confirm(TrustedContext c,String id,String digest,Set<String> affected,String key){
        require(c,"control");if(key==null||key.isBlank()||key.length()>256)throw new FcpException("INVALID_ARGUMENT","Invalid idempotency key");
        String receipt="grant."+hash(owner(c)+"\n"+key).substring(0,48);boolean committing=false;
        try(Connection db=source.getConnection()) {db.setAutoCommit(false);
            try {
                try(PreparedStatement q=statement(db,"SELECT state_json FROM fcp_runtime_state WHERE journal_id=? FOR UPDATE")){q.setString(1,receipt);try(ResultSet rs=q.executeQuery()){if(rs.next()){var saved=map(Json.parse(rs.getString(1)));if(!digest.equals(saved.get("digest")))throw new FcpException("IDEMPOTENCY_CONFLICT","Grant confirmation changed");return map(saved.get("result"));}}}
                Pending p=pending.get(id);if(p==null||!p.owner().equals(owner(c))||host.clock().instant().isAfter(p.expires()))throw new FcpException("PLAN_EXPIRED","Generate a new grant plan");
                if(!p.digest().equals(digest)||!affected.equals(Set.of(p.plugin())))throw new FcpException("CONFIRMATION_REQUIRED","Confirm exact grant plan");
                require(c,p.plugin());ensureTenant(db,p.tenant());
                try(PreparedStatement q=statement(db,"SELECT state_json FROM fcp_runtime_state WHERE journal_id='plugins' FOR UPDATE");ResultSet rs=q.executeQuery()) {
                    if(!rs.next())throw new FcpException("VERSION_CONFLICT","Installation state unavailable");var state=map(Json.parse(rs.getString(1)));
                    if(number(state.get("revision"))!=p.expectedRevision()||!p.packageDigest().equals(obj(obj(state,"installs"),p.plugin()).get("digest")))throw new FcpException("VERSION_CONFLICT","Installed package changed");
                }
                if(!rows(db,p.tenant(),p.plugin(),true).equals(p.previous()))throw new FcpException("VERSION_CONFLICT","Platform grants changed");
                for(var change:p.changes())try(PreparedStatement q=statement(db,"INSERT INTO fcp_runtime_grant(tenant_id,plugin_id,capability_id,operation,permission,data_scope,package_digest,revision,del) VALUES(?,?,?,?,?,?,?,1,?) ON DUPLICATE KEY UPDATE permission=VALUES(permission),package_digest=VALUES(package_digest),revision=revision+1,del=VALUES(del)")) {
                    q.setString(1,p.tenant());q.setString(2,p.plugin());q.setString(3,str(change.get("capabilityId")));q.setString(4,str(change.get("operation")));q.setString(5,str(change.get("permission")));q.setString(6,str(change.get("dataScope")));q.setString(7,p.packageDigest());q.setInt(8,Boolean.TRUE.equals(change.get("allow"))?0:1);q.executeUpdate();
                }
                Map<String,Object> result=Json.obj("operationId",receipt,"phase","COMPLETE","outcome","APPLIED","plugins",List.of(p.plugin()));
                try(PreparedStatement q=statement(db,"INSERT INTO fcp_runtime_state(journal_id,revision,state_json) VALUES(?,1,?)")){q.setString(1,receipt);q.setString(2,Json.write(Json.obj("digest",digest,"result",result)));q.executeUpdate();}
                try(PreparedStatement q=statement(db,"INSERT INTO fcp_runtime_audit(request_id,trace_id,tenant_id,principal_id,plugin_id,action,outcome,occurred_at) VALUES(?,?,?,?,?,'grant.confirm','APPLIED',?)")){q.setString(1,c.requestId());q.setString(2,c.traceId());q.setString(3,p.tenant());q.setString(4,c.principal().id());q.setString(5,p.plugin());q.setTimestamp(6,Timestamp.from(host.clock().instant()));q.executeUpdate();}
                committing=true;db.commit();return result;
            }catch(Exception e){try{db.rollback();}catch(SQLException ignored){}if(committing)throw new FcpException("OUTCOME_UNKNOWN","Grant result unknown; retry same confirmation key",e);if(e instanceof RuntimeException r)throw r;throw new FcpException("DATA_ACCESS","Unable to confirm grants",e);}
        }catch(SQLException e){throw new FcpException("DATA_ACCESS","Unable to access grant state",e);}
    }
    public Map<String,Object> operation(TrustedContext c,String id) {
        require(c,"control");if(!id.matches("grant\\.[a-f0-9]{48}"))throw new FcpException("NOT_FOUND","Unknown grant operation");
        try(Connection db=source.getConnection();PreparedStatement q=statement(db,"SELECT state_json FROM fcp_runtime_state WHERE journal_id=?")) {
            q.setString(1,id);try(ResultSet rows=q.executeQuery()){if(!rows.next())throw new FcpException("NOT_FOUND","Grant operation unavailable");return map(map(Json.parse(rows.getString(1))).get("result"));}
        }catch(SQLException e){throw new FcpException("DATA_ACCESS","Unable to read grant operation",e);}
    }
    private static List<PluginManifest.Capability> all(PluginManifest m){List<PluginManifest.Capability> result=new ArrayList<>(m.provides());result.addAll(m.requires());return result;}
    private static void tenant(String tenant){if(tenant==null||!tenant.matches("[A-Za-z0-9_-]{1,20}"))throw new FcpException("INVALID_ARGUMENT","Invalid target tenant");}
    private static void ensureTenant(Connection c,String tenant)throws SQLException {try(PreparedStatement q=statement(c,"SELECT tenant_id FROM sys_tenant WHERE tenant_id=? AND status='0' AND del_flag='0' AND (expire_time IS NULL OR expire_time>CURRENT_TIMESTAMP)")){q.setString(1,tenant);try(ResultSet rs=q.executeQuery()){if(!rs.next())throw new FcpException("PERMISSION_DENIED","Target tenant unavailable");}}}
    private static List<Object> rows(Connection c,String tenant,String plugin)throws SQLException {return rows(c,tenant,plugin,false);}
    private static List<Object> rows(Connection c,String tenant,String plugin,boolean lock)throws SQLException {List<Object> result=new ArrayList<>();try(PreparedStatement q=statement(c,"SELECT capability_id,operation,permission,data_scope,revision,del,package_digest FROM fcp_runtime_grant WHERE tenant_id=? AND plugin_id=? ORDER BY capability_id,operation,data_scope LIMIT 257"+(lock?" FOR UPDATE":""))){q.setString(1,tenant);q.setString(2,plugin);try(ResultSet r=q.executeQuery()){while(r.next()){if(result.size()>=256)throw new FcpException("BUSY","Grant cardinality limit");result.add(Json.obj("capabilityId",r.getString(1),"operation",r.getString(2),"permission",r.getString(3),"dataScope",r.getString(4),"revision",r.getLong(5),"allow",r.getInt(6)==0,"packageDigest",r.getString(7)));}}}return result;}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException {var q=c.prepareStatement(sql);q.setQueryTimeout(5);return q;}
    private static String owner(TrustedContext c){return c.tenantId()+":"+c.principal().kind()+":"+c.principal().id();}
    private static String hash(String value){return VerifiedPackageRepository.hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
