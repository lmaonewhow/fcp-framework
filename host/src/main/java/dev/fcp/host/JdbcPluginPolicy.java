package dev.fcp.host;

import fcp.FcpException;
import fcp.host.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.Objects;

/** Fresh exact-match grants. Absence, deletion, and SQL failure all fail closed. */
public final class JdbcPluginPolicy implements PluginPolicy {
    private final DataSource source;
    public JdbcPluginPolicy(DataSource source) {this.source=Objects.requireNonNull(source);}
    public Permission permission(TrustedContext context, HostCapabilities.Grant grant) {
        boolean task=context.principal().kind()==TrustedContext.Kind.TASK;
        String sql=task?
            "SELECT g.permission,GREATEST(g.revision,t.revision) FROM fcp_runtime_grant g JOIN fcp_runtime_task_grant t ON t.tenant_id=g.tenant_id AND t.plugin_id=g.plugin_id AND t.capability_id=g.capability_id AND t.operation=g.operation AND t.data_scope=g.data_scope WHERE g.tenant_id=? AND g.plugin_id=? AND g.capability_id=? AND g.operation=? AND g.data_scope=? AND g.del=0 AND t.task_id=? AND t.del=0 AND t.expires_at>CURRENT_TIMESTAMP AND g.package_digest=(SELECT JSON_UNQUOTE(JSON_EXTRACT(state_json,?)) FROM fcp_runtime_state WHERE journal_id='plugins')":
            "SELECT permission,revision FROM fcp_runtime_grant WHERE tenant_id=? AND plugin_id=? AND capability_id=? AND operation=? AND data_scope=? AND del=0 AND package_digest=(SELECT JSON_UNQUOTE(JSON_EXTRACT(state_json,?)) FROM fcp_runtime_state WHERE journal_id='plugins')";
        try(Connection c=source.getConnection();PreparedStatement q=c.prepareStatement(sql)) {
            if(!c.getAutoCommit())throw new FcpException("TRANSACTION_MODE","Fresh grant check requires independent connection");
            q.setQueryTimeout(5);q.setString(1,context.tenantId());q.setString(2,grant.pluginId());q.setString(3,grant.capability());q.setString(4,grant.operation());q.setString(5,grant.dataScope());if(task)q.setString(6,context.principal().id());q.setString(task?7:6,"$.installs."+fcp.Json.write(grant.pluginId())+".digest");
            try(ResultSet rows=q.executeQuery()) {if(!rows.next())return new Permission(false,"",0);return new Permission(true,rows.getString(1),rows.getLong(2));}
        } catch(SQLException failure) {throw new FcpException("DATA_ACCESS","Unable to revalidate plugin grants",failure);}
    }
    public void audit(HostCapabilities.Audit e) {
        try(Connection c=source.getConnection();PreparedStatement q=c.prepareStatement(
            "INSERT INTO fcp_runtime_audit(request_id,trace_id,tenant_id,principal_id,plugin_id,action,outcome,occurred_at) VALUES(?,?,?,?,?,?,?,?)")) {
            if(!c.getAutoCommit())throw new FcpException("TRANSACTION_MODE","Audit requires independent connection");
            q.setQueryTimeout(5);q.setString(1,e.requestId());q.setString(2,e.traceId());q.setString(3,e.tenantId());q.setString(4,e.principalId());q.setString(5,e.pluginId());q.setString(6,e.action());q.setString(7,e.outcome());q.setTimestamp(8,Timestamp.from(e.occurredAt()));q.executeUpdate();
        } catch(SQLException failure) {throw new FcpException("DATA_ACCESS","Unable to persist plugin audit",failure);}
    }
}
