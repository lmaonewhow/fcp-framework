import dev.fcp.deploy.Migrations;
import java.nio.file.*;
import java.util.*;

/** Parser and package-integrity checks only. MySQL migration execution is a separate gate. */
public final class MigrationRegression {
    static int count;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);count++;System.out.println("PASS "+name);}
    static void reject(String sql,String name){try{Migrations.statements(sql);throw new AssertionError(name);}catch(IllegalArgumentException expected){check(true,name);}}
    public static void main(String[] args)throws Exception{
        var scripts=Migrations.load(Path.of(args[0]));check(scripts.size()==4,"all four locked migration files verified");
        check(scripts.get(0).statements().isEmpty()&&scripts.get(1).statements().isEmpty(),"baseline native grants cannot execute through incremental runner");
        check(scripts.get(3).statements().isEmpty(),"ingestion native grants cannot execute through the DDL-only runner");
        reject("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1,1);","unreviewed native grants rejected by incremental parser");
        check(scripts.get(2).statements().size()==2,"persistent state migration has two reviewed DDL statements");
        check(Migrations.statements("-- header\n CREATE TABLE fcp_x (id INT, note VARCHAR(50) DEFAULT 'a;b'); #tail\n").size()==1,"quoted semicolon and SQL comments handled");
        check(Migrations.statements("CREATE TABLE fcp_x (s VARCHAR(40) DEFAULT 'a'';b'); CREATE TABLE fcp_y (id INT);").size()==2,"escaped SQL quote preserves statement boundary");
        reject("USE other;","database switching rejected");reject("DROP TABLE fcp_x;","destructive DDL rejected");
        reject("CREATE TABLE sys_user (id INT);","host table creation rejected");
        reject("/*!50000 DROP TABLE fcp_x */;","MySQL executable comments rejected");
        reject("CREATE TABLE fcp_x (s VARCHAR(50) DEFAULT 'broken);","unterminated literal rejected");
        reject("/* incomplete","unterminated comment rejected");
        reject("DELIMITER $$\nCREATE PROCEDURE p() SELECT 1;","stored-program delimiter rejected");
        Path temp=Files.createTempDirectory("fcp-migration-check-");
        try{
            try(var files=Files.list(Path.of(args[0]))){for(Path path:files.toList())Files.copy(path,temp.resolve(path.getFileName()));}
            Files.writeString(temp.resolve("V003__persistent_plugin_state.sql"),"-- altered\n",StandardOpenOption.APPEND);
            try{Migrations.load(temp);throw new AssertionError("Changed SQL accepted");}catch(IllegalArgumentException expected){check(true,"script checksum drift rejected before JDBC connection");}
            Files.delete(temp.resolve("V002__fcp_permissions.sql"));
            try{Migrations.load(temp);throw new AssertionError("Missing SQL accepted");}catch(IllegalArgumentException expected){check(true,"missing locked SQL file rejected");}
        }finally{try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("RESULT "+count+" migration package checks PASS; database execution NOT RUN");
    }
}
