package org.dromara.fcp.school;

import dev.fcp.dual.JdbcRecordStore;
import dev.fcp.school.SchoolFacade;
import dev.fcp.school.JdbcPluginStateStore;
import fcp.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import dev.fcp.host.RuntimeBundle;
import java.util.*;

@Configuration
public class FcpSchoolConfiguration {
    @Bean(destroyMethod="close")
    public RuntimeBundle fcpRuntimeBundle(Environment environment) throws Exception {
        String[] profiles=environment.getActiveProfiles();
        if(profiles.length==0 || !"fcp".equals(profiles[profiles.length-1]))
            throw new IllegalStateException("FCP must be the final active profile; use dev,fcp or prod,fcp");
        return RuntimeBundle.extract(FcpSchoolConfiguration.class.getClassLoader());
    }
    @Bean(destroyMethod="close")
    public SchoolFacade fcpSchool(RuntimeBundle bundle,DataSource dataSource,FcpSchoolIdentity identity)throws Exception {
        verifySchema(dataSource);
        return new SchoolFacade(bundle.root(),new JdbcRecordStore(dataSource),(context,target,operation)->{
            // Re-resolve host grants at dispatch and Engine.Context.atomic() commit boundaries.
            SchoolFacade.Identity current=identity.current();
            if(!current.scope().equals(context.scope())||!current.subject().equals(context.attributes.get("subject")))
                throw new FcpException("FORBIDDEN","Host identity changed during the request");
            String action=target.startsWith("evaluation.stats/")?"analyze":operation;
            if(!current.can(action))throw new FcpException("FORBIDDEN","Permission revoked");
            Set<String> captured=new HashSet<>();
            for(Object value:Schema.arr(context.attributes,"classes"))captured.add(Schema.str(value));
            if(!current.classes().equals(captured)||current.can("allClasses")!=Boolean.TRUE.equals(context.attributes.get("allClasses")))
                throw new FcpException("FORBIDDEN","Teaching scope changed; refresh before retrying");
        },new JdbcPluginStateStore(dataSource),false,128,identity::assertCurrent);
    }
    private static void verifySchema(DataSource dataSource) throws Exception {
        try (var connection=dataSource.getConnection()) {
            var metadata=connection.getMetaData();
            if (!metadata.getDatabaseProductName().equalsIgnoreCase("MySQL") || metadata.getDatabaseMajorVersion()<8)
                throw new IllegalStateException("FCP native integration currently requires MySQL 8+");
            for (String sql:List.of(
                "SELECT scope_id,kind,record_id,class_id,body_json FROM fcp_dual_record WHERE 1=0",
                "SELECT scope_id,subject_id,request_key,fingerprint,result_json FROM fcp_dual_request WHERE 1=0",
                "SELECT scope_id,kind,record_id,event_json FROM fcp_dual_audit WHERE 1=0",
                "SELECT scope_id,subject_id,class_id FROM fcp_eval_assignment WHERE 1=0",
                "SELECT scope_id,plugin_id,enabled,revision,updated_by FROM fcp_plugin_state WHERE 1=0",
                "SELECT scope_id,plugin_id,revision,enabled,actor FROM fcp_plugin_state_audit WHERE 1=0")) {
                try (var statement=connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(5);
                    try (var ignored=statement.executeQuery()) { /* Fail fast on an incomplete deployment. */ }
                }
            }
        }
    }
}
