package org.dromara.fcp.education;

import cn.dev33.satoken.stp.StpUtil;
import fcp.education.EducationScalars;
import fcp.education.FoundationCapabilities;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;

/** Discovery only. This does not grant access to any education row or school. */
@Component
public final class NativeFoundationIdentity {
    public record Actor(String tenant, String actor) {}
    public static final class AuthorityFailure extends RuntimeException {
        public final boolean unavailable;
        AuthorityFailure(boolean unavailable) { super("Education authority unavailable or denied"); this.unavailable=unavailable; }
    }
    private final DataSource source;
    public NativeFoundationIdentity(DataSource source) { this.source=source; }
    public Actor current() {
        StpUtil.checkLogin();
        try {
            return new Actor(EducationScalars.tenantId(LoginHelper.getTenantId()),
                EducationScalars.text(LoginHelper.getUserIdStr(),100));
        } catch (IllegalArgumentException invalid) { throw new AuthorityFailure(false); }
    }
    public Actor requireDiscovery() { return requireDiscovery(FoundationCapabilities.VIEW_PERMISSION); }
    /** Exact server-owned module permission. Never pass a request parameter here. */
    public Actor requireDiscovery(String permission) {
        if (!java.util.Set.of(FoundationCapabilities.VIEW_PERMISSION,"education:roster:view").contains(permission)) throw new AuthorityFailure(false);
        StpUtil.checkLogin();
        final Actor actor;
        try {
            actor=new Actor(EducationScalars.tenantId(LoginHelper.getTenantId()),
                EducationScalars.text(LoginHelper.getUserIdStr(),100));
        } catch (IllegalArgumentException invalid) { throw new AuthorityFailure(false); }
        // Fresh native membership, active tenant/user/role/menu, exact permission. No cached
        // Sa-Token wildcard, super-admin, legacy tenant normalization, or all-schools shortcut.
        String sql="SELECT u.tenant_id, CAST(u.user_id AS CHAR), m.perms FROM sys_user u "
            +"JOIN sys_tenant t ON t.tenant_id=u.tenant_id "
            +"JOIN sys_user_role ur ON ur.user_id=u.user_id "
            +"JOIN sys_role r ON r.role_id=ur.role_id AND r.tenant_id=u.tenant_id "
            +"JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id "
            +"LEFT JOIN sys_tenant_package p ON p.package_id=t.package_id "
            +"WHERE CAST(u.tenant_id AS BINARY)=CAST(? AS BINARY) "
            +"AND CAST(t.tenant_id AS BINARY)=CAST(? AS BINARY) "
            +"AND CAST(r.tenant_id AS BINARY)=CAST(? AS BINARY) "
            +"AND CAST(CAST(u.user_id AS CHAR) AS BINARY)=CAST(? AS BINARY) "
            +"AND u.status='0' AND u.del_flag='0' AND t.status='0' AND t.del_flag='0' "
            +"AND (t.expire_time IS NULL OR t.expire_time>CURRENT_TIMESTAMP) "
            +"AND r.status='0' AND r.del_flag='0' AND m.status='0' "
            +"AND CAST(m.perms AS BINARY)=CAST(? AS BINARY) "
            +"AND (CAST(t.tenant_id AS BINARY)=CAST('000000' AS BINARY) OR "
            +"(p.status='0' AND p.del_flag='0' AND FIND_IN_SET(CAST(m.menu_id AS CHAR),p.menu_ids)>0)) LIMIT 1";
        try (Connection c=source.getConnection()) {
            if (!c.getAutoCommit()) throw new AuthorityFailure(true);
            try (PreparedStatement q=c.prepareStatement(sql)) {
                q.setQueryTimeout(5);
                q.setString(1,actor.tenant());q.setString(2,actor.tenant());q.setString(3,actor.tenant());
                q.setString(4,actor.actor());q.setString(5,permission);
                try (ResultSet rows=q.executeQuery()) {
                    if (!rows.next() || !actor.tenant().equals(rows.getString(1))
                        || !actor.actor().equals(rows.getString(2))
                        || !permission.equals(rows.getString(3))) throw new AuthorityFailure(false);
                }
            }
        } catch (SQLException failed) { throw new AuthorityFailure(true); }
        return actor;
    }
}
