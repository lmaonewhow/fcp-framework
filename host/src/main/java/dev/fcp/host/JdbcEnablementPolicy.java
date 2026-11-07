package dev.fcp.host;

import fcp.*;
import fcp.control.EnablementPolicy;
import fcp.host.TrustedContext;
import fcp.plugin.PluginManifest;
import fcp.runtime.VerifiedPackageRepository;
import javax.sql.DataSource;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only platform approvals, separate from the manager's own business privileges. */
public final class JdbcEnablementPolicy implements EnablementPolicy {
    private final DataSource source;
    public JdbcEnablementPolicy(DataSource source){this.source=Objects.requireNonNull(source);}
    public String verify(TrustedContext context,PluginManifest manifest,String digest,Map<String,PluginManifest> closure) {
        List<Object> material=new ArrayList<>();material.add(digest);
        try(Connection c=source.getConnection()) {
            if(!c.getAutoCommit())throw new FcpException("TRANSACTION_MODE","Fresh approval requires independent connection");
            for(var requirement:manifest.requires()) {
                String capability=requirement.id(),operation="invoke";
                if(capability.startsWith("host.storage.")) {
                    operation=switch(capability.substring("host.storage.".length())){case "get","list"->"read";case "put"->"write";case "delete"->"delete";case "restore"->"restore";default->"denied";};capability="storage";
                }
                List<Object> scopes=new ArrayList<>();
                try(PreparedStatement q=c.prepareStatement("SELECT data_scope,revision FROM fcp_runtime_grant WHERE tenant_id=? AND plugin_id=? AND capability_id=? AND operation=? AND permission=? AND package_digest=? AND del=0 ORDER BY data_scope LIMIT 257")) {
                    q.setQueryTimeout(5);q.setString(1,context.tenantId());q.setString(2,manifest.pluginId());q.setString(3,capability);q.setString(4,operation);q.setString(5,"fcp:plugin:"+requirement.permission());q.setString(6,digest);
                    try(ResultSet rows=q.executeQuery()){while(rows.next()){if(scopes.size()>=256)throw new FcpException("CAPABILITY_NOT_GRANTED","Approval scope limit");scopes.add(List.of(rows.getString(1),rows.getLong(2)));}}
                }
                if(scopes.isEmpty())throw new FcpException("CAPABILITY_NOT_GRANTED","Required capability has no platform approval: "+requirement.id());
                material.add(List.of(requirement.id(),scopes));
            }
            return VerifiedPackageRepository.hash(Json.write(material).getBytes(StandardCharsets.UTF_8));
        }catch(SQLException error){throw new FcpException("DATA_ACCESS","Unable to verify platform capability approvals",error);}
    }
}
