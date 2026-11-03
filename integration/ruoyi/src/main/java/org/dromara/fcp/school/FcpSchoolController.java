package org.dromara.fcp.school;

import cn.dev33.satoken.annotation.SaCheckPermission;
import dev.fcp.school.SchoolFacade;
import dev.fcp.dual.HostResponse;
import fcp.Json;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.SpringBootVersion;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;

/** Install inside the existing host. Do not add authentication-ignore rules for these endpoints. */
@RestController
@RequestMapping(value="/fcp/school",produces=MediaType.APPLICATION_JSON_VALUE)
public class FcpSchoolController {
    private final SchoolFacade school;
    private final FcpSchoolIdentity identity;
    private final DataSource dataSource;
    private final ApplicationContext application;
    public FcpSchoolController(SchoolFacade school,FcpSchoolIdentity identity,DataSource dataSource,ApplicationContext application){
        this.school=school;this.identity=identity;this.dataSource=dataSource;this.application=application;
    }
    private ResponseEntity<String> ok(Object data){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(HostResponse.ok(data));}
    @GetMapping("/bootstrap") @SaCheckPermission("fcp:eval:list")
    public ResponseEntity<String> bootstrap(){return ok(school.describe(identity.current()));}
    @PostMapping("/evaluation/{operation}") @SaCheckPermission("fcp:eval:list")
    public ResponseEntity<String> invoke(@PathVariable("operation") String operation,@RequestBody String body){return ok(school.invoke(identity.current(),operation,body));}
    @PostMapping("/composition") @SaCheckPermission("fcp:eval:manage")
    public ResponseEntity<String> compose(@RequestBody String body){return ok(school.compose(identity.current(),body));}
    @GetMapping("/runtime") @SaCheckPermission("fcp:eval:manage")
    public ResponseEntity<String> runtime(){return ok(school.metrics(identity.current()));}
    @GetMapping("/host-evidence") @SaCheckPermission("fcp:eval:manage")
    public ResponseEntity<String> evidence()throws Exception{
        try(Connection c=dataSource.getConnection();
            java.io.InputStream resource=FcpSchoolController.class.getResourceAsStream("/fcp/native-assembly.json")){
            if(resource==null)throw new IllegalStateException("Native build provenance is missing");
            Map<String,Object> provenance=fcp.Schema.map(Json.parse(new String(resource.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
            return ok(Json.obj("implementation","ruoyi-native-host-0.5.0-dev.1","springBoot",SpringBootVersion.getVersion(),
                    "buildId",provenance.get("buildId"),"springContext",application.getClass().getName(),"backendVersion","5.4.1",
                    "backendCommit","64100cf1ffaeec78e36344d4617f6cb828ef093f","database",c.getMetaData().getDatabaseProductName(),
                    "databaseVersion",c.getMetaData().getDatabaseProductVersion(),"databaseName",c.getCatalog(),"subject",identity.current().subject()));
        }
    }
}
