import fcp.*;
import fcp.state.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual pinned Windows JDBC/DLL and filesystem. Process kill, not physical power loss. */
public final class WindowsFileStateRegression {
    static int checks;
    static final PluginStateStore.Key KEY=new PluginStateStore.Key("tenant-a","stats");
    static void check(boolean test,String message) {if(!test)throw new AssertionError(message);System.out.println("PASS "+(++checks)+" "+message);}
    static Connection connection(FilePluginStateStore store)throws Exception {
        Field backend=FilePluginStateStore.class.getDeclaredField("sqlite");backend.setAccessible(true);
        Object value=backend.get(store);Field connection=value.getClass().getDeclaredField("connection");connection.setAccessible(true);
        return (Connection)connection.get(value);
    }
    static void sql(Connection connection,String sql)throws Exception {try(Statement statement=connection.createStatement()){statement.execute(sql);}}
    static String scalar(Connection connection,String sql)throws Exception {try(Statement statement=connection.createStatement();ResultSet result=statement.executeQuery(sql)){if(!result.next())throw new AssertionError("missing result");return result.getString(1);}}
    static void unavailable(Runnable action,String message) {
        try {action.run();throw new AssertionError(message);}catch(FcpException failure){check(failure.code.equals("STATE_UNAVAILABLE"),message);}
    }
    static void rejected(Path path,String message)throws Exception {
        try(var ignored=new FilePluginStateStore(path,10000)){throw new AssertionError(message);}catch(IOException expected){check(true,message);}
    }
    static void child(String[] args)throws Exception {
        if(args[0].equals("--conversion-pending")) {
            Constructor<FilePluginStateStore> constructor=FilePluginStateStore.class.getDeclaredConstructor(Path.class,int.class,boolean.class);constructor.setAccessible(true);
            try(var store=constructor.newInstance(Path.of(args[1]),10000,true)) {
                Connection db=connection(store);db.setAutoCommit(false);
                sql(db,"INSERT INTO fcp_file_state VALUES('tenant-a','stats',0,17)");
                String digest=FilePluginStateStore.legacyPlan(Path.of(args[1]),10000);
                try(PreparedStatement insert=db.prepareStatement("INSERT INTO fcp_file_meta VALUES('legacy_sha256',?)")){insert.setString(1,digest);insert.executeUpdate();}
                System.out.println("READY");System.out.flush();System.in.read();
            }
            return;
        }
        try(var store=new FilePluginStateStore(Path.of(args[1]),10000)) {
            Connection db=connection(store);
            sql(db,"PRAGMA cache_size=1");sql(db,"PRAGMA cache_spill=ON");
            db.setAutoCommit(false);
            sql(db,"UPDATE fcp_file_state SET enabled=1,revision=revision+1");
            if(args[0].equals("--committed"))db.commit();
            System.out.println("READY");System.out.flush();
            // Parent kills this process while the transaction/host lock is still live.
            System.in.read();
        }
    }
    static void killAt(Path directory,String phase)throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        Process child=new ProcessBuilder(java,"-Dorg.sqlite.tmpdir="+System.getProperty("org.sqlite.tmpdir"),"-cp",System.getProperty("java.class.path"),"WindowsFileStateRegression",phase,directory.toString()).redirectErrorStream(true).start();
        ExecutorService reader=Executors.newSingleThreadExecutor();
        try {
            Future<String> ready=reader.submit(()->new BufferedReader(new InputStreamReader(child.getInputStream())).readLine());
            String line=ready.get(20,TimeUnit.SECONDS);
            if(!"READY".equals(line))throw new AssertionError("Crash child failed: "+line);
            child.destroyForcibly();check(child.waitFor(10,TimeUnit.SECONDS),phase+" actual child killed and reaped");
            long start=System.nanoTime(),deadline=start+5_000_000_000L;
            for(;;) {
                try(var channel=FileChannel.open(directory.resolve("plugin-state.lock"),StandardOpenOption.WRITE)) {
                    try(var lock=channel.tryLock()){if(lock!=null)break;}
                }
                if(System.nanoTime()>=deadline)throw new AssertionError("Killed child retained exclusive lock");
                Thread.sleep(10);
            }
            System.out.println("MEASURE post-kill exclusive-lock recovery ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        } finally {child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);reader.shutdownNow();}
    }
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].startsWith("--")){child(args);return;}
        if(!System.getProperty("os.name").startsWith("Windows"))throw new IllegalStateException("Native Windows test required");
        Path root=Files.createTempDirectory("fcp-windows-state-");
        try {
            Path crash=root.resolve("crash");
            try(var store=new FilePluginStateStore(crash,10000)) {
                for(int i=0;i<128;i++)store.getOrCreate(new PluginStateStore.Key("tenant-a","plugin-"+i),false);
                store.getOrCreate(KEY,false);
                check(scalar(connection(store),"PRAGMA synchronous").equals("3"),"effective synchronous EXTRA");
                check(scalar(connection(store),"PRAGMA journal_mode").equals("delete"),"effective rollback journal DELETE");
            }
            killAt(crash,"--uncommitted");
            try(var store=new FilePluginStateStore(crash,10000)) {
                check(store.getOrCreate(KEY,true).equals(new PluginStateStore.State(false,1)),"spilled uncommitted transaction rolls back after kill");
                check(scalar(connection(store),"PRAGMA integrity_check").equals("ok"),"recovered database integrity verified");
            }
            killAt(crash,"--committed");
            try(var store=new FilePluginStateStore(crash,10000)) {
                check(store.getOrCreate(KEY,false).equals(new PluginStateStore.State(true,2)),"committed revision survives kill before close");
                sql(connection(store),"PRAGMA query_only=ON");
                unavailable(()->store.compareAndSet(KEY,2,false,"test"),"read-only SQL failure rejects acknowledgment");
                unavailable(()->store.getOrCreate(KEY,false),"read-only failure poisons cached reads until reopen");
            }
            try(var store=new FilePluginStateStore(crash,10000)) {
                check(store.getOrCreate(KEY,false).equals(new PluginStateStore.State(true,2)),"failed write preserves last committed value after reopen");
                Connection db=connection(store);sql(db,"PRAGMA max_page_count="+scalar(db,"PRAGMA page_count"));
                boolean full=false;
                for(int i=0;i<1000;i++) {
                    final int index=i;
                    try{store.getOrCreate(new PluginStateStore.Key("tenant-"+"x".repeat(90),"p"+index+"y".repeat(90)),false);}
                    catch(FcpException failure){if(!failure.code.equals("STATE_UNAVAILABLE"))throw failure;full=true;break;}
                }
                check(full,"actual SQLITE_FULL reached using bounded database page limit");
                unavailable(()->store.getOrCreate(KEY,false),"full failure poisons store until verified reopen");
            }
            try(var store=new FilePluginStateStore(root.resolve("capacity"),1)) {
                store.getOrCreate(KEY,false);
                try{store.getOrCreate(new PluginStateStore.Key("tenant-b","stats"),true);throw new AssertionError("capacity");}
                catch(FcpException expected){check(expected.code.equals("RESOURCE_LIMIT"),"capacity bound rejects new key without storage mutation");}
            }
            Path legacy=root.resolve("legacy");Files.createDirectory(legacy);
            byte[] original=Json.write(Json.obj("format",1,"states",List.of(Json.obj("scope","tenant-a","plugin","stats","enabled",false,"revision",17)))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(legacy.resolve("plugin-state.json"),original);
            rejected(legacy,"legacy JSON never silently converted at startup");
            String digest=FilePluginStateStore.legacyPlan(legacy,8);
            try{FilePluginStateStore.migrateLegacy(legacy,8,"0".repeat(64));throw new AssertionError("changed plan");}
            catch(IOException expected){check(!Files.exists(legacy.resolve("plugin-state.sqlite")),"changed conversion plan rejected before database creation");}
            FilePluginStateStore.migrateLegacy(legacy,8,digest);
            check(Arrays.equals(original,Files.readAllBytes(legacy.resolve("plugin-state.json"))),"conversion retains original JSON bytes");
            check(Arrays.equals(original,Files.readAllBytes(legacy.resolve("plugin-state.json.backup-"+digest))),"conversion backup checksum and bytes verified");
            try(var store=new FilePluginStateStore(legacy,8)) {
                check(store.getOrCreate(KEY,true).equals(new PluginStateStore.State(false,17)),"conversion preserves exact value and revision");
                store.compareAndSet(KEY,17,true,"test");
            }
            FilePluginStateStore.migrateLegacy(legacy,8,digest);
            try(var store=new FilePluginStateStore(legacy,8)){check(store.getOrCreate(KEY,false).revision()==18,"repeated conversion never resets later revisions");}
            Path sqliteBackup=root.resolve("closed-state-backup.sqlite");
            Files.copy(legacy.resolve("plugin-state.sqlite"),sqliteBackup);
            Path sqliteRestore=root.resolve("sqlite-restored");Files.createDirectory(sqliteRestore);
            Files.copy(sqliteBackup,sqliteRestore.resolve("plugin-state.sqlite"));
            Files.copy(legacy.resolve("plugin-state.json"),sqliteRestore.resolve("plugin-state.json"));
            try(var store=new FilePluginStateStore(sqliteRestore,8)) {
                check(store.getOrCreate(KEY,false).equals(new PluginStateStore.State(true,18)),"closed SQLite backup restores current value and revision independently");
                check(scalar(connection(store),"PRAGMA integrity_check").equals("ok"),"restored SQLite database integrity verified");
            }
            Path restore=root.resolve("restored");Files.createDirectory(restore);
            Files.copy(legacy.resolve("plugin-state.json.backup-"+digest),restore.resolve("plugin-state.json"));
            FilePluginStateStore.migrateLegacy(restore,8,digest);
            try(var store=new FilePluginStateStore(restore,8)){check(store.getOrCreate(KEY,true).equals(new PluginStateStore.State(false,17)),"original backup restored into a separate directory with value/revision parity");}
            Path interrupted=root.resolve("interrupted-conversion");Files.createDirectory(interrupted);
            Files.write(interrupted.resolve("plugin-state.json"),original);
            killAt(interrupted,"--conversion-pending");
            rejected(interrupted,"interrupted conversion cannot acknowledge uncommitted imported state");
            FilePluginStateStore.migrateLegacy(interrupted,8,digest);
            try(var store=new FilePluginStateStore(interrupted,8)){check(store.getOrCreate(KEY,true).equals(new PluginStateStore.State(false,17)),"explicit retry recovers interrupted import transaction with exact parity");}
            Files.writeString(legacy.resolve("plugin-state.json"),"{corrupted");
            rejected(legacy,"legacy source drift blocks startup after conversion");
            Files.writeString(crash.resolve("plugin-state.sqlite"),"corrupted SQLite file");
            rejected(crash,"corrupt SQLite rejects startup without default state");
            Path unknown=root.resolve("unknown-format");
            try(var store=new FilePluginStateStore(unknown,8)){sql(connection(store),"PRAGMA user_version=99");}
            rejected(unknown,"unknown SQLite format rejects startup");
            System.setProperty("org.sqlite.lib.path",root.toString());
            try {rejected(root.resolve("override"),"native library override rejected before database access");}
            finally {System.clearProperty("org.sqlite.lib.path");}
            System.out.println("RESULT "+checks+" Windows file-state checks PASS; process-kill recovery is not physical power-loss proof");
        } finally {try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
