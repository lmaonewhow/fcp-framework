package org.dromara.fcp.plugin;

import cn.dev33.satoken.stp.StpUtil;
import dev.fcp.host.PluginPolicy;
import fcp.FcpException;
import fcp.host.*;
import org.dromara.common.satoken.utils.LoginHelper;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** RuoYi-specific identity/role queries stay in this adapter. No cached admin bypass. */
public final class NativePluginHost implements HostCapabilities {
    private final DataSource source;
    private final PluginPolicy policy;
    private final Clock clock;
    public NativePluginHost(DataSource source, PluginPolicy policy) {
        this.source=Objects.requireNonNull(source);this.policy=Objects.requireNonNull(policy);this.clock=Clock.systemUTC();
    }
    public Descriptor descriptor() {
        return new Descriptor("ruoyi-vue-plus", "5.6.2", "fcp.host/1", "embedded-single-instance",
            Map.of("identity","1.0","authorization","1.0","audit","1.0","ui.slot.dashboard.status","1.0"));
    }
    public Clock clock() { return clock; }
    /** Only controllers call this after native Sa-Token authentication. */
    public TrustedContext current() {
        StpUtil.checkLogin();
        String tenant=LoginHelper.getTenantId(), user=LoginHelper.getUserIdStr();
        UserPolicy fresh=readUser(tenant,user);
        return new TrustedContext(UUID.randomUUID().toString(),UUID.randomUUID().toString(),tenant,
            new TrustedContext.Principal(TrustedContext.Kind.USER,user),fresh.revision(),fresh.scopes(),clock.instant().plusSeconds(60));
    }
    public Set<String> permissions(TrustedContext context) {
        if(context.principal().kind()!=TrustedContext.Kind.USER)throw new FcpException("PERMISSION_DENIED","User context required");
        return readUser(context.tenantId(),context.principal().id()).permissions();
    }
    public Decision authorize(TrustedContext context, Grant grant) {
        // Infrastructure recovery is distinct from business tasks and never invokes business/storage.
        if(context.principal().kind()==TrustedContext.Kind.TASK) {
            boolean recovery=context.principal().id().equals("fcp-control-recovery") && grant.capability().equals("plugin.lifecycle")
                && Set.of("reconcile","enable","disable","status").contains(grant.operation())
                && grant.dataScope().equals("tenant:"+context.tenantId());
            if(context.principal().id().equals("fcp-control-recovery"))return new Decision(recovery && tenantActive(context.tenantId()),0);
            if(grant.capability().equals("system.release")||grant.capability().equals("plugin.lifecycle")||grant.capability().equals("storage.schema"))return new Decision(false,0);
            PluginPolicy.Permission task=policy.permission(context,normalize(grant));
            return new Decision(tenantActive(context.tenantId())&&task!=null&&task.granted(),task==null?0:task.revision());
        }
        UserPolicy user=readUser(context.tenantId(),context.principal().id());
        if(grant.capability().equals("system.release")) {
            boolean supported=Set.of("view","manage").contains(grant.operation());
            boolean platform=context.tenantId().equals("000000");
            boolean scope=grant.dataScope().equals("tenant:"+context.tenantId());
            return new Decision(supported&&platform&&scope&&user.permissions().contains("fcp:release:"+grant.operation()),user.revision());
        }
        if(grant.capability().equals("plugin.lifecycle")) {
            String op=grant.operation();
            String permission=Set.of("status","view").contains(op)?"fcp:plugin:view":
                Set.of("install","uninstall").contains(op)?"fcp:plugin:install":"fcp:plugin:manage";
            boolean platform=!Set.of("install","uninstall").contains(op)||context.tenantId().equals("000000");
            boolean scope=grant.dataScope().equals("tenant:"+context.tenantId());
            return new Decision(platform&&scope&&user.permissions().contains(permission),user.revision());
        }
        if(grant.capability().equals("storage.schema")) {
            boolean allowed=context.tenantId().equals("000000")&&grant.operation().equals("migrate")&&user.permissions().contains("fcp:plugin:install")
                && (grant.dataScope().equals("tenant:"+context.tenantId())||grant.dataScope().matches("collection:[a-zA-Z][a-zA-Z0-9_.-]{0,63}"));
            return new Decision(allowed,user.revision());
        }
        Grant effective=normalize(grant);
        PluginPolicy.Permission plugin=policy.permission(context,effective);
        boolean scope=grant.dataScope().equals("tenant:"+context.tenantId())||user.scopes().contains(grant.dataScope());
        boolean allowed=plugin!=null&&plugin.granted()&&scope&&user.permissions().contains(plugin.userPermission());
        long revision=31*user.revision()+(plugin==null?0:plugin.revision());
        return new Decision(allowed,revision&0x1fffffffffffffL);
    }
    private static Grant normalize(Grant grant) {
        Grant effective=grant;
        if(grant.capability().startsWith("host.storage.") && grant.operation().equals("invoke")) {
            String operation=switch(grant.capability().substring("host.storage.".length())) {
                case "get","list"->"read";case "put"->"write";case "delete"->"delete";case "restore"->"restore";default->"denied";
            };
            effective=new Grant(grant.pluginId(),"storage",operation,grant.dataScope());
        }
        return effective;
    }
    public TrustedContext recoveryContext(String tenant) {
        return new TrustedContext(UUID.randomUUID().toString(),UUID.randomUUID().toString(),tenant,
            new TrustedContext.Principal(TrustedContext.Kind.TASK,"fcp-control-recovery"),0,Set.of(),clock.instant().plusSeconds(60));
    }
    private boolean tenantActive(String tenant) {
        try(Connection c=source.getConnection();PreparedStatement q=c.prepareStatement("SELECT tenant_id FROM sys_tenant WHERE tenant_id=? AND status='0' AND del_flag='0' AND (expire_time IS NULL OR expire_time>CURRENT_TIMESTAMP)")) {
            q.setQueryTimeout(5);q.setString(1,tenant);try(ResultSet rows=q.executeQuery()){return rows.next();}
        }catch(SQLException e){throw new FcpException("DATA_ACCESS","Cannot verify recovery tenant",e);}
    }
    public void audit(Audit event) { policy.audit(event); }
    private record UserPolicy(Set<String> permissions,Set<String> scopes,long revision) { }
    private UserPolicy readUser(String tenant,String user) {
        if(tenant==null||!tenant.matches("[A-Za-z0-9_-]{1,20}")||user==null||!user.matches("[1-9][0-9]{0,18}"))
            throw new FcpException("PERMISSION_DENIED","Invalid native identity");
        TreeSet<String> permissions=new TreeSet<>(),scopes=new TreeSet<>();
        try(Connection c=source.getConnection()) {
            if(!c.getAutoCommit())throw new FcpException("TRANSACTION_MODE","Fresh authorization requires independent connection");
            String active="SELECT u.user_id FROM sys_user u JOIN sys_tenant t ON t.tenant_id=u.tenant_id WHERE u.tenant_id=? AND u.user_id=? AND u.status='0' AND u.del_flag='0' AND t.status='0' AND t.del_flag='0' AND (t.expire_time IS NULL OR t.expire_time>CURRENT_TIMESTAMP)";
            try(PreparedStatement q=prepare(c,active,tenant,user);ResultSet rs=q.executeQuery()) {
                if(!rs.next())throw new FcpException("PERMISSION_DENIED","Account or tenant unavailable");
            }
            String roles="SELECT DISTINCT m.perms FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id JOIN sys_tenant t ON t.tenant_id=r.tenant_id LEFT JOIN sys_tenant_package p ON p.package_id=t.package_id WHERE r.tenant_id=? AND ur.user_id=? AND r.status='0' AND r.del_flag='0' AND m.status='0' AND (m.perms LIKE 'fcp:plugin:%' OR m.perms IN ('fcp:release:view','fcp:release:manage')) AND (t.tenant_id='000000' OR (p.status='0' AND p.del_flag='0' AND FIND_IN_SET(CAST(m.menu_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_bin,p.menu_ids COLLATE utf8mb4_bin)>0)) LIMIT 257";
            try(PreparedStatement q=prepare(c,roles,tenant,user);ResultSet rs=q.executeQuery()) {
                int rows=0;while(rs.next()) { if(++rows>256)throw new FcpException("PERMISSION_DENIED","Permission limit exceeded");
                    String value=rs.getString(1);if(value!=null)for(String p:value.split(","))if(p.trim().startsWith("fcp:plugin:")||Set.of("fcp:release:view","fcp:release:manage").contains(p.trim()))permissions.add(p.trim()); }
            }
            try(PreparedStatement q=prepare(c,"SELECT class_id FROM fcp_eval_assignment WHERE scope_id=? AND subject_id=? ORDER BY class_id LIMIT 257","tenant-"+tenant,user);ResultSet rs=q.executeQuery()) {
                while(rs.next()){scopes.add("class:"+rs.getString(1));if(scopes.size()>256)throw new FcpException("PERMISSION_DENIED","Scope limit exceeded");}
            }
            long revision=policyRevision(permissions,scopes);
            return new UserPolicy(Set.copyOf(permissions),Set.copyOf(scopes),revision);
        } catch(SQLException failure) { throw new FcpException("DATA_ACCESS","Unable to revalidate host permissions",failure); }
    }
    private static long policyRevision(Set<String> permissions,Set<String> scopes) {
        try {
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(
                fcp.Json.write(java.util.List.of(new java.util.ArrayList<>(new java.util.TreeSet<>(permissions)),new java.util.ArrayList<>(new java.util.TreeSet<>(scopes)))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(hash).getLong() & 0x1fffffffffffffL;
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private PreparedStatement prepare(Connection c,String sql,String tenant,String user)throws SQLException {
        PreparedStatement q=c.prepareStatement(sql);q.setQueryTimeout(5);q.setString(1,tenant);q.setString(2,user);return q;
    }
}
