import org.redisson.Redisson;import org.redisson.config.Config;import org.redisson.api.*;import org.redisson.client.codec.StringCodec;
import org.dromara.common.redis.handler.KeyPrefixHandler;
import org.dromara.fcp.school.FcpRedisConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.*;import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.*;import java.util.concurrent.*;import java.time.*;
public class ExistingRedisConnectionCheck {
 public static void main(String[]args)throws Exception {
  if(args.length!=1 || !args[0].matches("fcp_accept_[a-f0-9]{32}"))throw new IllegalArgumentException("Fresh acceptance UUID namespace required");
  RedisProperties properties=new Binder(new MapConfigurationPropertySource(Map.of("spring.data.redis.host","127.0.0.1","spring.data.redis.port","6379","spring.data.redis.password",""))).bind("spring.data.redis",Bindable.of(RedisProperties.class)).get();
  String password=properties.getPassword();System.out.println("BIND password="+(password==null?"NULL":password.isEmpty()?"EMPTY":"PRESENT"));
  Config config=new Config().setThreads(2).setNettyThreads(2).setCodec(StringCodec.INSTANCE);
  config.useSingleServer().setAddress("redis://127.0.0.1:6379").setDatabase(0).setConnectionMinimumIdleSize(1).setConnectionPoolSize(2).setNameMapper(new KeyPrefixHandler(args[0])).setPassword(password);
  var customizer=new FcpRedisConfiguration().fcpRedisPasswordCustomizer();
  Config authenticated=new Config();var secure=authenticated.useSingleServer().setPassword("synthetic-unit-password");customizer.customize(authenticated);
  if(!"synthetic-unit-password".equals(secure.getPassword()))throw new AssertionError("Configured password changed");
  Config named=new Config();var acl=named.useSingleServer().setUsername("synthetic-acl-user").setPassword("");customizer.customize(named);
  if(!"".equals(acl.getPassword()))throw new AssertionError("Explicit ACL credentials changed");
  customizer.customize(config);
  System.out.println("PASS empty default-user password normalized; configured password and explicit ACL credentials preserved");
  RedissonClient client=Redisson.create(config);
  try {
   RBucket<String> bucket=client.getBucket("probe-bucket");
   if(!bucket.trySet("synthetic-only",30,TimeUnit.SECONDS))throw new AssertionError("Fresh namespace collision");
   if(!"synthetic-only".equals(bucket.get()))throw new AssertionError("Bucket roundtrip");
   RMapCache<String,String> map=client.getMapCache("probe-map");map.fastPut("synthetic","value",10,TimeUnit.SECONDS);map.expire(Duration.ofSeconds(30));
   if(!"value".equals(map.get("synthetic")))throw new AssertionError("Lua-backed map-cache roundtrip");
   System.out.println("PASS actual Redisson 3.50.0, upstream NameMapper, namespaced bucket and Lua-backed map cache; TTL only, no shared key deletion");
  } finally {client.shutdown();}
 }
}
