package dev.fcp.host;

import fcp.*;
import fcp.host.*;
import fcp.plugin.PluginManifest;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Fresh digest-bound USER plus individually expiring TASK approvals. No legacy digest fallback. */
public final class JdbcVersionPluginPolicy implements VersionPluginPolicy {
    private final DataSource source;private final LiveIdentity identities;private final ManifestResolver manifests;
    private final ManagementAuthorization management;private final Clock clock;
    public JdbcVersionPluginPolicy(DataSource source,LiveIdentity identities,ManifestResolver manifests,ManagementAuthorization management,Clock clock){this.source=Objects.requireNonNull(source);this.identities=Objects.requireNonNull(identities);this.manifests=Objects.requireNonNull(manifests);this.management=Objects.requireNonNull(management);this.clock=Objects.requireNonNull(clock);}
    private String permission(Version version,String capability,String operation) {
        if(!operation.equals("invoke"))throw new FcpException("VERSION_GRANT_UNDECLARED","Only declared capability invocation can be approved");
        PluginManifest manifest=manifests.resolve(version);
        if(!manifest.pluginId().equals(version.pluginId())||!manifest.version().equals(version.version()))throw new FcpException("VERSION_GRANT_PACKAGE_MISMATCH","Verified manifest identity mismatch");
        Set<String> matches=new HashSet<>();
        for(var cap:concat(manifest.provides(),manifest.requires()))if(cap.id().equals(capability))matches.add("fcp:plugin:"+cap.permission());
        if(matches.size()!=1)throw new FcpException("VERSION_GRANT_UNDECLARED","Capability permission is missing or ambiguous");
        String permission=matches.iterator().next();if(permission.length()>128)throw new FcpException("VERSION_GRANT_UNDECLARED","Permission exceeds native grant bound");return permission;
    }
    private static List<PluginManifest.Capability> concat(List<PluginManifest.Capability>a,List<PluginManifest.Capability>b){var out=new ArrayList<>(a);out.addAll(b);return out;}
    private void actor(TrustedContext actor,String tenant){if(actor.principal().kind()!=TrustedContext.Kind.USER||!clock.instant().isBefore(actor.deadline()))throw new FcpException("PERMISSION_DENIED","Live USER approval identity required");management.require(actor,tenant);}
    private static String id(GrantKey k){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(List.of(k.tenant(),k.version().pluginId(),k.version().version(),k.version().digest(),k.capability(),k.operation(),k.dataScope(),k.principalKind().name(),k.principalId())).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public HostCapabilities.Decision authorize(TrustedContext ctx,Version version,HostCapabilities.Grant grant) {
        // JdbcPluginStorage performs an additional internal check after the exact host.storage.* capability check.
        // Resolve only declared, digest-approved storage capabilities; never approve a generic storage wildcard.
        if(grant.capability().equals("storage")) {
            List<String> targets=switch(grant.operation()){case "read"->List.of("host.storage.get","host.storage.list");case "write"->List.of("host.storage.put");case "delete"->List.of("host.storage.delete");case "restore"->List.of("host.storage.restore");default->List.of();};
            long revision=0;
            for(String target:targets)try {
                HostCapabilities.Decision decision=authorize(ctx,version,new HostCapabilities.Grant(grant.pluginId(),target,"invoke",grant.dataScope()));
                revision=revision(revision,decision.currentRevision());if(decision.allowed())return new HostCapabilities.Decision(true,revision);
            }catch(FcpException failure){if(!failure.code.equals("VERSION_GRANT_UNDECLARED"))throw failure;}
            return new HostCapabilities.Decision(false,revision);
        }
        if(!clock.instant().isBefore(ctx.deadline())||!grant.pluginId().equals(version.pluginId()))return new HostCapabilities.Decision(false,0);
        Identity identity=identities.current(ctx);if(!identity.active())return new HostCapabilities.Decision(false,identity.revision());
        boolean scope=grant.dataScope().equals("tenant:"+ctx.tenantId())||identity.dataScopes().contains(grant.dataScope());
        if(!scope)return new HostCapabilities.Decision(false,identity.revision());
        String permission=permission(version,grant.capability(),grant.operation());
        GrantKey base=new GrantKey(ctx.tenantId(),version,grant.capability(),grant.operation(),grant.dataScope(),TrustedContext.Kind.USER,"*");
        try(Connection c=source.getConnection()) {
            fresh(c);Approval platform=load(c,base,false);long revision=revision(identity.revision(),platform==null?0:platform.revision());
            if(!active(platform,permission))return new HostCapabilities.Decision(false,revision);
            if(ctx.principal().kind()==TrustedContext.Kind.USER)return new HostCapabilities.Decision(identity.permissions().contains(permission),revision);
            Approval task=load(c,new GrantKey(ctx.tenantId(),version,grant.capability(),grant.operation(),grant.dataScope(),TrustedContext.Kind.TASK,ctx.principal().id()),false);
            return new HostCapabilities.Decision(active(task,permission)&&task.expiresAt()!=null,revision(revision,task==null?0:task.revision()));
        }catch(SQLException e){throw new FcpException("DATA_ACCESS","Cannot verify version grants",e);}
    }
    private static long revision(long a,long b){return (31*a+b)&9007199254740991L;}
    private boolean active(Approval a,String permission){return a!=null&&a.allowed()&&a.permission().equals(permission)&&(a.expiresAt()==null||clock.instant().isBefore(a.expiresAt()));}
    @Override public Approval read(TrustedContext actor,GrantKey key){actor(actor,key.tenant());try(Connection c=source.getConnection()){fresh(c);return load(c,key,false);}catch(SQLException e){throw new FcpException("DATA_ACCESS","Cannot read version grant",e);}}
    @Override public Approval change(TrustedContext actor,GrantKey key,boolean allow,Instant expiry,long expected){
        actor(actor,key.tenant());if(expected<0||expected>=9007199254740991L)throw new IllegalArgumentException("Invalid revision");
        if(expiry!=null&&!clock.instant().isBefore(expiry))throw new IllegalArgumentException("Expiry must be in future");
        if(allow&&key.principalKind()==TrustedContext.Kind.TASK&&expiry==null)throw new IllegalArgumentException("TASK approval must expire");
        String permission=permission(key.version(),key.capability(),key.operation());boolean committing=false;
        try(Connection c=source.getConnection()) {
            fresh(c);c.setAutoCommit(false);
            try {
                Approval old=load(c,key,true);if((old==null?0:old.revision())!=expected)throw new FcpException("VERSION_GRANT_CONFLICT","Version grant changed");
                if(allow&&key.principalKind()==TrustedContext.Kind.TASK){Approval platform=load(c,new GrantKey(key.tenant(),key.version(),key.capability(),key.operation(),key.dataScope(),TrustedContext.Kind.USER,"*"),true);if(!active(platform,permission))throw new FcpException("VERSION_GRANT_DENIED","Platform package approval required before task approval");}
                long revision=expected+1;
                if(old==null)try(PreparedStatement p=statement(c,"INSERT INTO fcp_plugin_version_grant(grant_id,tenant_id,plugin_id,package_version,package_digest,capability_id,operation,data_scope,principal_kind,principal_id,permission,revision,del,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){int i=bindKey(p,key,1);p.setString(i++,permission);p.setLong(i++,revision);p.setInt(i++,allow?0:1);p.setTimestamp(i,expiry==null?null:Timestamp.from(expiry));p.executeUpdate();}
                else try(PreparedStatement p=statement(c,"UPDATE fcp_plugin_version_grant SET permission=?,revision=?,del=?,expires_at=? WHERE grant_id=? AND revision=?")){p.setString(1,permission);p.setLong(2,revision);p.setInt(3,allow?0:1);p.setTimestamp(4,expiry==null?null:Timestamp.from(expiry));p.setString(5,id(key));p.setLong(6,expected);if(p.executeUpdate()!=1)throw new FcpException("VERSION_GRANT_CONFLICT","Version grant changed");}
                try(PreparedStatement p=statement(c,"INSERT INTO fcp_runtime_audit(request_id,trace_id,tenant_id,principal_id,plugin_id,action,outcome,occurred_at) VALUES(?,?,?,?,?,?,?,?)")){p.setString(1,actor.requestId());p.setString(2,actor.traceId());p.setString(3,key.tenant());p.setString(4,actor.principal().id());p.setString(5,key.version().pluginId());p.setString(6,"version.grant."+key.version().digest());p.setString(7,(allow?"ALLOW:":"REVOKE:")+id(key));p.setTimestamp(8,Timestamp.from(clock.instant()));p.executeUpdate();}
                committing=true;c.commit();return new Approval(key,permission,revision,allow,expiry);
            }catch(SQLException|RuntimeException e){try{c.rollback();}catch(SQLException ignored){}throw e;}
        }catch(SQLException e){throw new FcpException(committing?"VERSION_GRANT_OUTCOME_UNKNOWN":e.getSQLState()!=null&&(e.getSQLState().startsWith("23")||e.getSQLState().startsWith("40"))?"VERSION_GRANT_CONFLICT":"DATA_ACCESS","Version grant write requires reconciliation",e);}
    }
    private Approval load(Connection c,GrantKey key,boolean lock)throws SQLException {
        String sql="SELECT tenant_id,plugin_id,package_version,package_digest,capability_id,operation,data_scope,principal_kind,principal_id,permission,revision,del,expires_at FROM fcp_plugin_version_grant WHERE grant_id=?"+(lock?" FOR UPDATE":"");
        try(PreparedStatement p=statement(c,sql)){p.setString(1,id(key));try(ResultSet r=p.executeQuery()){if(!r.next())return null;GrantKey found=new GrantKey(r.getString(1),new Version(r.getString(2),r.getString(3),r.getString(4)),r.getString(5),r.getString(6),r.getString(7),TrustedContext.Kind.valueOf(r.getString(8)),r.getString(9));if(!found.equals(key))throw new FcpException("VERSION_GRANT_IDENTITY","Stored grant identity mismatch");Timestamp expires=r.getTimestamp(13);long revision=r.getLong(11);int deleted=r.getInt(12);if(revision<1||revision>9007199254740991L||(deleted!=0&&deleted!=1))throw new FcpException("VERSION_GRANT_IDENTITY","Invalid stored grant revision/state");Approval a=new Approval(found,r.getString(10),revision,deleted==0,expires==null?null:expires.toInstant());if(r.next())throw new FcpException("VERSION_GRANT_IDENTITY","Duplicate version grant");return a;}}
    }
    private static int bindKey(PreparedStatement p,GrantKey key,int i)throws SQLException{p.setString(i++,id(key));p.setString(i++,key.tenant());p.setString(i++,key.version().pluginId());p.setString(i++,key.version().version());p.setString(i++,key.version().digest());p.setString(i++,key.capability());p.setString(i++,key.operation());p.setString(i++,key.dataScope());p.setString(i++,key.principalKind().name());p.setString(i++,key.principalId());return i;}
    private static void fresh(Connection c)throws SQLException{if(!c.getAutoCommit())throw new FcpException("TRANSACTION_MODE","Version grants require independent connection");}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{PreparedStatement p=c.prepareStatement(sql);p.setQueryTimeout(5);return p;}
}
