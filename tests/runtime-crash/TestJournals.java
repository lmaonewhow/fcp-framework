import fcp.*;
import fcp.control.*;
import fcp.storage.JdbcStateJournal;
import javax.sql.DataSource;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.*;

/** Test-only journal selection. Never opens an arbitrary URL, creates a schema, or deletes rows. */
final class TestJournals {
    static final List<String> SETTINGS=List.of("FCP_CRASH_TEST_URL","FCP_CRASH_TEST_USER","FCP_CRASH_TEST_PASSWORD","FCP_CRASH_TEST_DRIVER_CLASSPATH");
    static final Pattern URL=Pattern.compile("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):([0-9]{1,5})/(fcp_ci_crash_[a-f0-9]{12,40})(?:\\?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC)?");
    static boolean mysql;
    private static Source source;
    static boolean validate(Map<String,String> env)throws IOException {
        boolean configured=SETTINGS.stream().anyMatch(env::containsKey);if(!configured)return false;
        if(!"true".equals(env.get("GITHUB_ACTIONS")))throw new IOException("MySQL crash mode requires its disposable GITHUB_ACTIONS job");
        for(String name:SETTINGS)if(env.get(name)==null||env.get(name).isBlank())throw new IOException("All FCP_CRASH_TEST connection settings and driver classpath are required");
        Matcher match=URL.matcher(env.get(SETTINGS.get(0)));
        if(!match.matches()||Integer.parseInt(match.group(1))<1||Integer.parseInt(match.group(1))>65535)throw new IOException("MySQL crash mode requires loopback, explicit port, and a dedicated fcp_ci_crash_<random-hex> database");
        return true;
    }
    static void configure()throws IOException {
        mysql=validate(System.getenv());if(!mysql)return;
        try{Class.forName("com.mysql.cj.jdbc.Driver");}catch(ClassNotFoundException missing){throw new IOException("MySQL Connector/J must be present on the explicit driver classpath");}
        source=new Source();
    }
    static Map<String,Object> preflight()throws IOException {
        if(!mysql)return Map.of("backend","FILE");
        try(Connection c=source.getConnection()){
            Map<String,Object> result=new LinkedHashMap<>();result.put("backend","MYSQL");
            try(Statement statement=c.createStatement()){
                statement.setQueryTimeout(15);
                try(ResultSet r=statement.executeQuery("SELECT DATABASE(),VERSION()")){
                    if(!r.next()||r.getString(1)==null||!r.getString(1).matches("fcp_ci_crash_[a-f0-9]{12,40}"))throw new IOException("Wrong crash-test database");
                    result.put("database",r.getString(1));result.put("serverVersion",r.getString(2));
                }
                try(ResultSet r=statement.executeQuery("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='fcp_runtime_state'")){
                    if(!r.next()||!"InnoDB".equalsIgnoreCase(r.getString(1)))throw new IOException("Provision V005 fcp_runtime_state as InnoDB before running crash tests");
                    result.put("journalTableEngine",r.getString(1));
                }
                try(ResultSet r=statement.executeQuery("SELECT COUNT(*) FROM fcp_runtime_state")){
                    if(!r.next()||r.getLong(1)!=0)throw new IOException("Refusing nonempty crash-test journal database; provision a fresh dedicated database");
                    result.put("emptyAtStart",true);
                }
            }
            return result;
        }catch(SQLException failure){throw safe(failure);}
    }
    static void initialize(Path directory)throws IOException {
        if(!mysql)return;
        String prefix="crash_"+UUID.randomUUID().toString().replace("-","");
        CrashRecoveryTest.durable(directory.resolve("journal-ids.json"),Map.of("control",prefix+"_control","calls",prefix+"_calls"));
    }
    static String id(Path directory,String kind)throws IOException {
        if(!Set.of("control","calls").contains(kind))throw new IOException("Unknown test journal kind");
        String id=Schema.str(CrashRecoveryTest.read(directory.resolve("journal-ids.json")).get(kind));
        if(!id.matches("crash_[a-f0-9]{32}_"+kind))throw new IOException("Refusing a journal ID not owned by this crash-test fixture");
        return id;
    }
    static StateJournal open(Path directory,String kind)throws IOException {
        return mysql?new JdbcStateJournal(source,id(directory,kind)):new FileStateJournal(directory.resolve(kind));
    }
    static StateJournal.Snapshot read(Path directory,String kind)throws IOException {
        if(mysql)try(StateJournal journal=open(directory,kind)){return journal.load();}
        Path file=directory.resolve(kind).resolve("lifecycle.json");if(!Files.exists(file))return new StateJournal.Snapshot(0,null);
        Map<String,Object> raw=CrashRecoveryTest.read(file);return new StateJournal.Snapshot(Schema.number(raw.get("revision")),Schema.str(raw.get("json")));
    }
    static Map<String,Object> state(Path directory)throws IOException {
        var snapshot=read(directory,"control");return snapshot.json()==null?Map.of():Schema.map(Json.parse(snapshot.json()));
    }
    static void fork(Path original,Path target)throws IOException {
        if(!mysql)return;
        // The copied file initially contains the old IDs. Replace it before any target journal access.
        initialize(target);
        for(String kind:List.of("control","calls")){
            String originalId=id(original,kind),targetId=id(target,kind);
            if(originalId.equals(targetId))throw new IOException("Revocation branch must own separate journal rows");
            var expected=read(original,kind);
            // Fixed SQL only. Preserve JSON and storage revision, insert only new test-owned keys.
            try(Connection connection=source.getConnection();PreparedStatement copy=connection.prepareStatement("INSERT INTO fcp_runtime_state(journal_id,revision,state_json) SELECT ?,revision,state_json FROM fcp_runtime_state WHERE journal_id=?")){
                copy.setQueryTimeout(15);copy.setString(1,targetId);copy.setString(2,originalId);
                int inserted=copy.executeUpdate();if(inserted!=(expected.json()==null?0:1))throw new IOException("Crash snapshot row count mismatch");
            }catch(SQLException failure){throw safe(failure);}
            if(!read(target,kind).equals(expected))throw new IOException("Crash snapshot copy changed journal revision or JSON");
        }
    }
    static Map<String,Object> inspect(Path directory)throws IOException {
        if(!mysql)return Map.of("backend","FILE");
        Map<String,Object> result=new LinkedHashMap<>();result.put("backend","MYSQL");
        for(String kind:List.of("control","calls")){var snapshot=read(directory,kind);result.put(kind,Map.of("journalId",id(directory,kind),"revision",snapshot.revision(),"present",snapshot.json()!=null));}
        return result;
    }
    private static IOException safe(SQLException failure){return new IOException("Dedicated crash-test JDBC operation failed (SQLState="+failure.getSQLState()+", vendorCode="+failure.getErrorCode()+")");}
    private static final class Source implements DataSource {
        public Connection getConnection()throws SQLException {
            try{return DriverManager.getConnection(System.getenv("FCP_CRASH_TEST_URL"),System.getenv("FCP_CRASH_TEST_USER"),System.getenv("FCP_CRASH_TEST_PASSWORD"));}
            catch(SQLException failure){throw new SQLException("Dedicated crash-test connection failed",failure.getSQLState(),failure.getErrorCode());}
        }
        public Connection getConnection(String username,String password){throw new UnsupportedOperationException("Only the explicit test identity is allowed");}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter writer){throw new UnsupportedOperationException();}
        public void setLoginTimeout(int seconds){throw new UnsupportedOperationException();}public int getLoginTimeout(){return 0;}
        public Logger getParentLogger(){return Logger.getGlobal();}public <T>T unwrap(Class<T>type)throws SQLException{throw new SQLException("Not a wrapper");}public boolean isWrapperFor(Class<?>type){return false;}
    }
}
