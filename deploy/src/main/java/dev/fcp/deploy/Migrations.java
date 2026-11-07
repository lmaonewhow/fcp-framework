package dev.fcp.deploy;

import fcp.Json;
import fcp.Schema;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Restricted incremental migrator for the delivered, reviewed FCP SQL files.
 * This is a deployment tool, never called by the application or a plugin.
 * MySQL DDL may commit before failure. Dirty history is deliberately not auto-repaired.
 */
public final class Migrations {
    public record Script(int version,String name,String sha256,List<String> statements) {}
    record Applied(String name,String hash,String status) {}
    private static final String HISTORY="fcp_migration_history";
    private static String digest(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    public static List<String> statements(String sql) {
        List<String> result=new ArrayList<>();StringBuilder current=new StringBuilder();char quote=0;boolean line=false,block=false;
        for(int i=0;i<sql.length();i++){
            char c=sql.charAt(i),next=i+1<sql.length()?sql.charAt(i+1):0;
            if(line){if(c=='\n'){line=false;current.append(' ');}continue;}
            if(block){if(c=='*'&&next=='/'){block=false;i++;current.append(' ');}continue;}
            if(quote!=0){current.append(c);if(c=='\\'&&quote!='`'&&i+1<sql.length())current.append(sql.charAt(++i));
                else if(c==quote){if(next==quote){current.append(next);i++;}else quote=0;}continue;}
            if(c=='-'&&next=='-'&&(i+2>=sql.length()||Character.isWhitespace(sql.charAt(i+2)))){line=true;i++;continue;}
            if(c=='#'){line=true;continue;}
            if(c=='/'&&next=='*'){
                if(i+2<sql.length()&&sql.charAt(i+2)=='!')throw new IllegalArgumentException("Executable comments are not accepted");
                block=true;i++;continue;
            }
            if(c=='\''||c=='"'||c=='`'){quote=c;current.append(c);continue;}
            if(c==';'){append(result,current);current.setLength(0);}else current.append(c);
        }
        if(quote!=0||block)throw new IllegalArgumentException("Unterminated SQL literal or comment");
        append(result,current);return List.copyOf(result);
    }
    private static void append(List<String> statements,StringBuilder buffer){
        String text=buffer.toString().trim();if(text.isEmpty())return;
        // Incremental plugin-schema DDL only. No routines, global settings, database switching or native table DML.
        if(!Pattern.compile("(?is)^(CREATE\\s+TABLE|ALTER\\s+TABLE)\\s+`?fcp_[a-z0-9_]+`?\\s.*").matcher(text).matches())
            throw new IllegalArgumentException("Unapproved SQL statement category");
        if(Pattern.compile("(?i)\\b(DROP|TRUNCATE|RENAME|INTO\\s+OUTFILE|LOAD_FILE)\\b").matcher(text).find())
            throw new IllegalArgumentException("Destructive SQL requires a separate reviewed deployment");
        statements.add(text);
    }
    public static List<Script> load(Path directory)throws Exception{
        if(Files.isSymbolicLink(directory))throw new IllegalArgumentException("Symlink migration directory rejected");
        Path lock=directory.resolve("migrations.lock.json");
        if(Files.isSymbolicLink(lock)||Files.size(lock)>65536)throw new IllegalArgumentException("Unsafe migration lock");
        Map<String,Object> manifest=Schema.map(Json.parse(Files.readString(lock)));
        if(Schema.number(manifest.get("format"))!=1)throw new IllegalArgumentException("Unsupported migration lock");
        Map<String,Object> hashes=Schema.obj(manifest,"files");List<Script> result=new ArrayList<>();Set<Integer> versions=new HashSet<>();
        try(var stream=Files.list(directory)){
            for(Path path:stream.filter(p->p.getFileName().toString().endsWith(".sql")).sorted().toList()){
                String name=path.getFileName().toString();Matcher match=Pattern.compile("V(\\d{3,})__[a-z0-9_]+\\.sql").matcher(name);
                if(!match.matches()||Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.size(path)>2_097_152)throw new IllegalArgumentException("Unsafe migration file");
                int version=Integer.parseInt(match.group(1));byte[] bytes=Files.readAllBytes(path);String hash=digest(bytes);
                if(!hash.equals(hashes.get(name))||!versions.add(version))throw new IllegalArgumentException("Migration drift or duplicate version");
                // Baseline tables and native grant scripts are checksum-checked, never executed by this DDL-only runner.
                boolean grants=name.equals("V004__ingestion_permissions.sql")||name.equals("V006__runtime_plugin_permissions.sql");
                List<String> commands=version>=3&&!grants?statements(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)):List.of();
                if(version>=3&&!grants&&commands.isEmpty())throw new IllegalArgumentException("Empty migration");
                result.add(new Script(version,name,hash,commands));
            }
        }
        if(result.size()!=hashes.size()||!versions.containsAll(Set.of(1,2)))throw new IllegalArgumentException("Missing locked migration or baseline");
        result.sort(Comparator.comparingInt(Script::version));return List.copyOf(result);
    }
    private static boolean hasTable(Connection connection,String table)throws SQLException{
        try(ResultSet rows=connection.getMetaData().getTables(connection.getCatalog(),null,table,new String[]{"TABLE"})){
            while(rows.next())if(table.equals(rows.getString("TABLE_NAME")))return true;
            return false;
        }
    }
    private static Map<String,Applied> legacy(Connection connection)throws SQLException{
        if(!hasTable(connection,"fcp_install_history"))throw new SQLException("Verified 0.4.2 installation history required");
        Map<String,Applied> result=new HashMap<>();
        try(PreparedStatement query=connection.prepareStatement("SELECT stage,sha256,state FROM fcp_install_history")){
            query.setQueryTimeout(10);try(ResultSet rows=query.executeQuery()){
                while(rows.next()){String name=rows.getString(1);result.put(name,new Applied(name,rows.getString(2),rows.getString(3)));}
            }
        }return result;
    }
    private static Map<Integer,Applied> history(Connection connection)throws SQLException{
        Map<Integer,Applied> result=new HashMap<>();if(!hasTable(connection,HISTORY))return result;
        try(PreparedStatement query=connection.prepareStatement("SELECT version_num,script_name,sha256,state FROM fcp_migration_history")){
            query.setQueryTimeout(10);try(ResultSet rows=query.executeQuery()){
                while(rows.next())result.put(rows.getInt(1),new Applied(rows.getString(2),rows.getString(3),rows.getString(4)));
            }
        }return result;
    }
    private static List<Object> plan(Connection connection,List<Script> scripts)throws SQLException{
        Map<String,Applied> legacy=legacy(connection);Map<Integer,Applied> applied=history(connection);Set<Integer> known=new HashSet<>();List<Object> actions=new ArrayList<>();
        for(Script script:scripts){
            known.add(script.version());Applied row=applied.get(script.version());boolean current=row!=null;
            if(row==null)row=legacy.get(script.name());
            if(row!=null && (!row.name().equals(script.name())||!row.hash().equals(script.sha256())||!row.status().equals("APPLIED")))
                throw new SQLException("Migration history is dirty or changed; operator review required");
            if(script.version()<3||script.name().equals("V004__ingestion_permissions.sql")||script.name().equals("V006__runtime_plugin_permissions.sql")){
                if(row==null)throw new SQLException("Native baseline/grants must be installed by the reviewed deployment installer");continue;
            }
            actions.add(Json.obj("version",script.version(),"name",script.name(),"sha256",script.sha256(),
                "action",current?"ALREADY_APPLIED":row!=null?"ADOPT_VERIFIED_INSTALL":"APPLY","statements",script.statements().size()));
        }
        if(!known.containsAll(applied.keySet()))throw new SQLException("Database is newer than this migration package");
        return actions;
    }
    private static void execute(Connection connection,String sql)throws SQLException{
        try(Statement statement=connection.createStatement()){statement.setQueryTimeout(60);statement.execute(sql);}
    }
    private static void record(Connection connection,Script script,String status)throws SQLException{
        try(PreparedStatement insert=connection.prepareStatement("INSERT INTO fcp_migration_history(version_num,script_name,sha256,state) VALUES(?,?,?,?)")){
            insert.setQueryTimeout(10);insert.setInt(1,script.version());insert.setString(2,script.name());insert.setString(3,script.sha256());insert.setString(4,status);insert.executeUpdate();
        }
    }
    private static void status(Connection connection,int version,String status)throws SQLException{
        try(PreparedStatement update=connection.prepareStatement("UPDATE fcp_migration_history SET state=?,updated_at=CURRENT_TIMESTAMP(6) WHERE version_num=?")){
            update.setQueryTimeout(10);update.setString(1,status);update.setInt(2,version);if(update.executeUpdate()!=1)throw new SQLException("History row disappeared");
        }
    }
    public static void main(String[] args){
        try{run(args);}catch(Exception failure){
            System.err.println("MIGRATION BLOCKED: "+failure.getClass().getSimpleName()+". No automatic repair. Inspect deployment history with an authorized DBA.");
            if(failure instanceof SQLException sql)System.err.println("SQLState="+sql.getSQLState()+" vendorCode="+sql.getErrorCode());
            System.exit(2);
        }
    }
    private static void run(String[] args)throws Exception{
        if(args.length<3||args.length>4)throw new IllegalArgumentException("Usage: Migrations <sql-directory> <private-jdbc.properties> <expected-database> [--apply=<plan-sha256>]");
        Path directory=Path.of(args[0]),credentials=Path.of(args[1]);String database=args[2];
        if(!database.matches("[A-Za-z0-9_]{1,64}")||Files.isSymbolicLink(credentials)||!Files.isRegularFile(credentials))throw new IllegalArgumentException("Invalid deployment input");
        if(Files.getFileStore(credentials).supportsFileAttributeView("posix"))for(PosixFilePermission p:Files.getPosixFilePermissions(credentials))
            if(p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_"))throw new IllegalArgumentException("Credential file must be owner-only");
        Properties properties=new Properties();try(var reader=Files.newBufferedReader(credentials)){properties.load(reader);}
        String url=properties.getProperty("jdbc.url"),user=properties.getProperty("jdbc.user"),password=properties.getProperty("jdbc.password");
        if(url==null||!url.startsWith("jdbc:mysql:")||user==null||password==null||password.isEmpty())throw new IllegalArgumentException("Incomplete JDBC configuration");
        String expected=null;if(args.length==4){if(!args[3].matches("--apply=[a-f0-9]{64}"))throw new IllegalArgumentException("Approval must match exact plan digest");expected=args[3].substring(8);}
        List<Script> scripts=load(directory);
        try(Connection connection=DriverManager.getConnection(url,user,password)){
            if(!database.equals(connection.getCatalog())||!connection.getAutoCommit()||!connection.getMetaData().getDatabaseProductName().equals("MySQL")||connection.getMetaData().getDatabaseMajorVersion()<8)
                throw new SQLException("Unexpected database or transaction mode");
            String lock="fcp-migrate-"+digest(database.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,40);boolean acquired=false;
            try{
                if(expected!=null){
                    try(PreparedStatement query=connection.prepareStatement("SELECT GET_LOCK(?,10)")){
                        query.setString(1,lock);query.setQueryTimeout(15);try(ResultSet result=query.executeQuery()){
                            if(!result.next()||result.getInt(1)!=1||result.wasNull())throw new SQLException("Another migration owns the deployment lock");acquired=true;
                        }
                    }
                    execute(connection,"SET SESSION lock_wait_timeout=10");
                }
                List<Object> plan=plan(connection,scripts);String encoded=Json.write(Json.obj("database",database,"actions",plan));
                String digest=digest(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                System.out.println(Json.write(Json.obj("mode",expected==null?"PLAN_ONLY":"APPLY","planSha256",digest,"plan",Json.parse(encoded))));
                if(expected==null)return;
                if(!expected.equals(digest))throw new SQLException("Approved plan changed; review the new plan");
                execute(connection,"CREATE TABLE IF NOT EXISTS fcp_migration_history(version_num INT PRIMARY KEY,script_name VARCHAR(180) NOT NULL,sha256 CHAR(64) NOT NULL,state VARCHAR(16) NOT NULL,updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
                for(Object value:plan){
                    Map<String,Object> action=Schema.map(value);String mode=Schema.str(action.get("action"));if(mode.equals("ALREADY_APPLIED"))continue;
                    int version=(int)Schema.number(action.get("version"));Script script=scripts.stream().filter(x->x.version()==version).findFirst().orElseThrow();
                    if(mode.equals("ADOPT_VERIFIED_INSTALL")){record(connection,script,"APPLIED");continue;}
                    record(connection,script,"RUNNING");
                    try{for(String sql:script.statements())execute(connection,sql);status(connection,version,"APPLIED");}
                    catch(SQLException failure){try{status(connection,version,"FAILED");}catch(SQLException mark){failure.addSuppressed(mark);}throw failure;}
                }
                System.out.println("MIGRATION APPLIED. Validate the application schema before accepting traffic.");
            }finally{if(acquired)try(PreparedStatement release=connection.prepareStatement("SELECT RELEASE_LOCK(?)")){release.setString(1,lock);release.setQueryTimeout(5);release.execute();}}
        }
    }
}
