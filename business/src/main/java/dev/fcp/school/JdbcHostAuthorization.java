package dev.fcp.school;

import dev.fcp.dual.ScopeNames;
import fcp.FcpException;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Fresh FCP grants from pinned RuoYi 5.6.2 tables. Never trusts cached role permissions.
 * Sa-Token still authenticates the session before this adapter is called.
 * Explicit FCP roles are required, including for native super-admin accounts.
 */
public final class JdbcHostAuthorization {
    private final DataSource dataSource;
    private static final Set<String> ALLOWED=Set.of("fcp:eval:list","fcp:eval:get","fcp:eval:history",
        "fcp:eval:create","fcp:eval:analyze","fcp:eval:manage","fcp:eval:allClasses","fcp:ui:admin",
        "fcp:plugin:view","fcp:plugin:manage","fcp:plugin:install",
        "fcp:ingest:view","fcp:ingest:import","fcp:ingest:manage","fcp:ingest:allClasses");
    public JdbcHostAuthorization(DataSource dataSource){this.dataSource=Objects.requireNonNull(dataSource);}
    private PreparedStatement prepare(Connection connection,String sql,String tenant,String subject)throws SQLException {
        PreparedStatement statement=connection.prepareStatement(sql);statement.setQueryTimeout(5);
        statement.setString(1,tenant);statement.setString(2,subject);return statement;
    }
    public SchoolFacade.Identity resolve(String tenant,String subject){
        String scope=ScopeNames.tenant(tenant);ScopeNames.subject(subject);
        if(!subject.matches("[1-9][0-9]{0,18}"))throw new FcpException("FORBIDDEN","Native user ID must be canonical numeric");
        Set<String> grants=new HashSet<>(),classes=new HashSet<>();
        try(Connection connection=dataSource.getConnection()){
            if(!connection.getAutoCommit())throw new FcpException("TRANSACTION_MODE","Fresh authorization requires an independent connection");
            String active="SELECT u.user_id FROM sys_user u JOIN sys_tenant t ON t.tenant_id=u.tenant_id " +
                "WHERE u.tenant_id=? AND u.user_id=? AND u.status='0' AND u.del_flag='0' " +
                "AND t.status='0' AND t.del_flag='0' AND (t.expire_time IS NULL OR t.expire_time>CURRENT_TIMESTAMP)";
            try(PreparedStatement query=prepare(connection,active,tenant,subject);ResultSet result=query.executeQuery()){
                if(!result.next())throw new FcpException("FORBIDDEN","Account or tenant is inactive, expired or outside this scope");
            }
            String permissions="SELECT DISTINCT m.perms FROM sys_user_role ur " +
                "JOIN sys_role r ON r.role_id=ur.role_id JOIN sys_role_menu rm ON rm.role_id=r.role_id " +
                "JOIN sys_menu m ON m.menu_id=rm.menu_id JOIN sys_tenant t ON t.tenant_id=r.tenant_id " +
                "LEFT JOIN sys_tenant_package p ON p.package_id=t.package_id " +
                "WHERE r.tenant_id=? AND ur.user_id=? AND r.status='0' AND r.del_flag='0' AND m.status='0' AND m.perms LIKE 'fcp:%' " +
                "AND (t.tenant_id='000000' OR (p.status='0' AND p.del_flag='0' AND FIND_IN_SET(" +
                "CAST(m.menu_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_bin,p.menu_ids COLLATE utf8mb4_bin)>0)) LIMIT 257";
            try(PreparedStatement query=prepare(connection,permissions,tenant,subject);ResultSet result=query.executeQuery()){
                int rows=0;
                while(result.next()){
                    if(++rows>256)throw new FcpException("FORBIDDEN","Authorization grant cardinality exceeds bound");
                    String permission=result.getString(1);
                    if(permission!=null)for(String item:permission.split(","))if(ALLOWED.contains(item.trim()))grants.add(item.trim());
                }
            }
            if(!grants.contains("fcp:eval:allClasses")||(grants.contains("fcp:ingest:view")&&!grants.contains("fcp:ingest:allClasses"))){
                try(PreparedStatement query=connection.prepareStatement(
                    "SELECT class_id FROM fcp_eval_assignment WHERE scope_id=? AND subject_id=? ORDER BY class_id LIMIT 33")){
                    query.setQueryTimeout(5);query.setString(1,scope);query.setString(2,subject);
                    try(ResultSet result=query.executeQuery()){while(result.next())classes.add(result.getString(1));}
                    if(classes.size()>32)throw new FcpException("FORBIDDEN","Class grant exceeds supported bound");
                }
            }
            return new SchoolFacade.Identity(scope,subject,grants,classes);
        }catch(SQLException failure){throw new FcpException("DATA_ACCESS","Unable to revalidate native authorization",failure);}
    }
}
