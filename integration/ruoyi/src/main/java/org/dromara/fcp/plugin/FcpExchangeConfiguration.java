package org.dromara.fcp.plugin;

import fcp.exchange.*;
import fcp.host.*;
import fcp.runtime.EngineRuntimePort;
import fcp.storage.JdbcStateJournal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Opt-in synthetic fixtures, never inferred school data or implicit plugin/task authorization.
 * Fixed startup configuration is controlled by the deployment owner, not request JSON.
 */
@Configuration
@ConditionalOnProperty(name={"fcp.runtime.enabled","fcp.exchange.enabled"},havingValue="true")
public class FcpExchangeConfiguration {
    private static String identifier(Environment env,String key,String regex){String s=env.getRequiredProperty(key);if(!s.matches(regex))throw new IllegalArgumentException("Invalid "+key);return s;}
    @Bean(destroyMethod="close") public ExchangeService fcpExchangeService(NativePluginHost host,EngineRuntimePort runtime,DataSource source,Environment env)throws Exception {
        String classId=identifier(env,"FCP_EXCHANGE_FIXTURE_CLASS","[A-Za-z0-9_.-]{1,100}");
        List<ExchangeService.Config> configs=List.of(
            new ExchangeService.Config("roster-fixture","roster","class:"+classId,"campus-v1-csv","campus-v1-fixture","1",Set.of("student_code","full_name","class_code"),"campus-fixture","roster-fixture-sync"),
            new ExchangeService.Config("exam-fixture","mock-exam","class:"+classId,"campus-v1-csv","exam-v1-fixture","1",Set.of("exam_code","title","class_code"),"exam-fixture","exam-fixture-sync"));
        Map<String,ExchangeService.Connector> connectors=new HashMap<>();
        for(String kind:List.of("campus","exam")){String endpoint=env.getProperty("FCP_EXCHANGE_"+kind.toUpperCase(Locale.ROOT)+"_FIXTURE_API");if(endpoint!=null&&!endpoint.isBlank())connectors.put(kind+"-fixture",new LoopbackConnector(URI.create(endpoint)));}
        ExchangeService.ExecutionPort execution=new ExchangeService.ExecutionPort(){
            public String version(TrustedContext c,String plugin){var enabled=runtime.enabledVersion(c,plugin);return enabled.version()+":"+enabled.digest();}
            public Object call(TrustedContext c,String plugin,String operation,Map<String,Object> input,String key){return runtime.invoke(c,plugin,operation,input,key,null);}
        };
        return new ExchangeService(host,execution,new JdbcStateJournal(source,"exchange-fixtures"),configs,connectors);
    }
    @Bean(destroyMethod="close") @ConditionalOnProperty(name="fcp.exchange.schedules.enabled",havingValue="true")
    public FixedTaskScheduler fcpExchangeScheduler(ExchangeService exchange,NativePluginHost host,Environment env){
        String tenant=identifier(env,"FCP_EXCHANGE_FIXTURE_TENANT","[A-Za-z0-9_-]{1,20}");
        String scope="class:"+identifier(env,"FCP_EXCHANGE_FIXTURE_CLASS","[A-Za-z0-9_.-]{1,100}");
        long interval=Long.parseLong(env.getProperty("FCP_EXCHANGE_FIXTURE_INTERVAL_SECONDS","60"));if(interval<5||interval>86400)throw new IllegalArgumentException("Fixture interval must be 5..86400 seconds");
        List<FixedTaskScheduler.Schedule> schedules=new ArrayList<>();
        for(String kind:List.of("roster","exam")){String api=env.getProperty("FCP_EXCHANGE_"+(kind.equals("roster")?"CAMPUS":"EXAM")+"_FIXTURE_API");if(api!=null&&!api.isBlank())schedules.add(new FixedTaskScheduler.Schedule(tenant,kind+"-fixture-sync",kind+"-fixture",Duration.ofSeconds(interval)));}
        if(schedules.isEmpty())throw new IllegalArgumentException("Scheduled fixture requires an explicitly configured loopback API");
        return new FixedTaskScheduler(exchange,(t,task)->{
            var context=new TrustedContext(UUID.randomUUID().toString(),UUID.randomUUID().toString(),t,new TrustedContext.Principal(TrustedContext.Kind.TASK,task),0,Set.of(scope),host.clock().instant().plusSeconds(60));
            String plugin=task.equals("roster-fixture-sync")?"roster":"mock-exam";
            host.require(context,new HostCapabilities.Grant(plugin,"exchange","task",scope));return context;
        },schedules,(schedule,error)->LoggerFactory.getLogger(FcpExchangeConfiguration.class).warn("FCP fixture task {} failed ({}); cursor retained",schedule.config(),error.getClass().getSimpleName()));
    }
}
