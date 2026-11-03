package org.dromara.fcp.school;

import cn.dev33.satoken.stp.StpUtil;
import dev.fcp.school.JdbcHostAuthorization;
import dev.fcp.school.SchoolFacade;
import fcp.FcpException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

/** Native authentication plus freshly queried FCP authorization. No caller-supplied identity. */
@Component
public final class FcpSchoolIdentity {
    private final JdbcHostAuthorization authorization;
    public FcpSchoolIdentity(DataSource dataSource){authorization=new JdbcHostAuthorization(dataSource);}
    public void assertCurrent(SchoolFacade.Identity captured){
        if(!current().equals(captured))throw new FcpException("FORBIDDEN","Host authorization changed; refresh before retrying");
    }
    public SchoolFacade.Identity current(){
        StpUtil.checkLogin();
        return authorization.resolve(LoginHelper.getTenantId(),LoginHelper.getUserIdStr());
    }
}
