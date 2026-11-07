import fcp.*;import fcp.control.*;import fcp.storage.JdbcStateJournal;
import javax.sql.DataSource;import java.sql.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.security.*;import java.time.*;import java.util.*;

/** CI-only test helper. Ages one genuinely generated synthetic export snapshot through CAS.
 * Never compiled into the production artifact, never deletes rows/business/audit/receipt data,
 * and never changes the production 24-hour policy or application Clock.
 */
public final class NativeExportRetentionProbe {
 static String env(String k){String v=System.getenv(k);if(v==null||v.isBlank())throw new IllegalArgumentException("Missing test environment");return v;}
 static final class Source implements DataSource {
  final String url,user,password;Source(String u,String n,String p){url=u;user=n;password=p;}
  public Connection getConnection()throws SQLException{return DriverManager.getConnection(url,user,password);}public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Override denied");}
  public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter w){throw new UnsupportedOperationException();}public void setLoginTimeout(int n){}public int getLoginTimeout(){return 5;}public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}public <T>T unwrap(Class<T> c)throws SQLException{throw new SQLException("Unwrap denied");}public boolean isWrapperFor(Class<?> c){return false;}
 }
 static Map<String,Object> object(Object value){return new LinkedHashMap<>(Schema.map(value));}
 static Object canonical(Object v){if(v instanceof Map<?,?> m){Map<String,Object> out=new TreeMap<>();m.forEach((k,x)->out.put((String)k,canonical(x)));return out;}if(v instanceof List<?> l)return l.stream().map(NativeExportRetentionProbe::canonical).toList();return v;}
 static String preserved(Map<String,Object> state)throws Exception{Map<String,Object> out=new TreeMap<>();state.forEach((k,v)->{if(!k.startsWith("download:"))out.put(k,canonical(v));});return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(out).getBytes(StandardCharsets.UTF_8)));}
 static String handle(String s){if(!s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Invalid fixture handle");return "download:"+s;}
 public static void main(String[] args){try{run(args);}catch(Exception e){System.out.println(Json.write(Map.of("status","FAIL","error",e.getClass().getSimpleName())));System.exit(2);}}
 static void run(String[] args)throws Exception {
  if(!"true".equals(System.getenv("GITHUB_ACTIONS")))throw new IllegalArgumentException("CI only");
  String url=env("FCP_EXCHANGE_RETENTION_TEST_URL");if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:33306/fcp_ci_[a-f0-9]{12}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC"))throw new IllegalArgumentException("Dedicated native CI database required");
  if(args.length<2||!Set.of("age","check").contains(args[0]))throw new IllegalArgumentException("Fixed probe operation required");
  var source=new Source(url,env("FCP_EXCHANGE_RETENTION_TEST_USER"),env("FCP_EXCHANGE_RETENTION_TEST_PASSWORD"));String key=handle(args[1]);
  try(var journal=new JdbcStateJournal(source,"exchange-fixtures")){
   var snapshot=journal.load();var state=object(Json.parse(snapshot.json()));var export=object(state.get(key));
   if(!"roster-fixture".equals(export.get("config"))||!"[\"000000\",\"USER\",\"1\"]".equals(export.get("owner"))||!"24h-v1".equals(export.get("retentionPolicy")))throw new IllegalArgumentException("Not an authenticated native synthetic export");
   if(args[0].equals("age")){
    if(args.length!=2||!export.containsKey("rows")||export.containsKey("cleanupAudit"))throw new IllegalArgumentException("Only generated snapshot content may be aged");
    Instant created=Instant.parse(Schema.str(export.get("createdAt"))),expires=Instant.parse(Schema.str(export.get("expires")));
    if(!expires.equals(created.plusSeconds(86400))||created.isBefore(Instant.now().minusSeconds(3600)))throw new IllegalArgumentException("Original production 24h policy not observed");
    for(Object row:Schema.list(export.get("rows"))){var r=Schema.map(row);if(!r.keySet().equals(Set.of("student_code"))||!Set.of("s1","api1","task1","task2").contains(r.get("student_code")))throw new IllegalArgumentException("Synthetic field projection required");}
    String retained=preserved(state);Instant aged=Instant.now().minusSeconds(86401);export.put("createdAt",aged.toString());export.put("expires",aged.plusSeconds(86400).toString());state.put(key,export);journal.compareAndSet(snapshot.revision(),Json.write(state));
    System.out.println(Json.write(Map.of("status","AGED_FIXTURE","preservedDigest",retained,"originalRetentionHours",24)));
   }else{
    if(args.length!=4||!args[3].matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Preservation proof required");
    var keep=object(state.get(handle(args[2])));boolean pending=export.containsKey("rows");
    if(!preserved(state).equals(args[3])||!keep.containsKey("rows")||!Instant.now().isBefore(Instant.parse(Schema.str(keep.get("expires")))))throw new IllegalStateException("Unrelated state or fresh snapshot changed");
    if(!pending){var audit=Schema.map(export.get("cleanupAudit"));if(!"export.cleanup".equals(audit.get("action"))||!"COMPLETE".equals(audit.get("outcome"))||!export.containsKey("contentDigest"))throw new IllegalStateException("Durable cleanup audit missing");}
    System.out.println(Json.write(Map.of("status",pending?"PENDING":"PASS","retainedRecords","previews,receipts,cursors and journal audit unchanged","freshSnapshotRetained",true,"cleanupAuditDurable",!pending)));
   }
  }
 }
}
