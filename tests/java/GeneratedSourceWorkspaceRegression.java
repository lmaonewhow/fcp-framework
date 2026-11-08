import dev.fcp.generator.GeneratedSourceWorkspace;
import dev.fcp.generator.RuoYiGeneratedMetadata;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public final class GeneratedSourceWorkspaceRegression {
    private static int assertions;
    private static final String SOURCE="main/java/demo/Thing.java", TARGET="integration/ruoyi/src/main/java/demo/Thing.java";
    private static final String OWNER="tenant:1:user:1";
    @FunctionalInterface interface Throwing { void run() throws Exception; }
    static void check(boolean condition,String label) { assertions++;if(!condition)throw new AssertionError(label); }
    static void rejects(Throwing work,String label) throws Exception { try{work.run();throw new AssertionError("Accepted: "+label);}catch(IOException expected){assertions++;} }
    static byte[] zip(String... entries) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(out)){for(int i=0;i<entries.length;i+=2){z.putNextEntry(new ZipEntry(entries[i]));z.write(entries[i+1].getBytes(StandardCharsets.UTF_8));z.closeEntry();}}return out.toByteArray();
    }
    static Map<String,Object> plan(GeneratedSourceWorkspace w,String value)throws Exception{return w.plan(OWNER,List.of("1"),zip(SOURCE,value));}
    static Map<String,Object> apply(GeneratedSourceWorkspace w,String value)throws Exception{return w.apply(OWNER,(String)plan(w,value).get("planId"));}
    static String id(Map<String,Object> m){return (String)m.get("operationId");}
    static String status(Map<String,Object> p){return (String)((Map<?,?>)((List<?>)p.get("files")).get(0)).get("status");}
    static Path root()throws Exception{return Files.createTempDirectory("fcp-generator-regression-");}
    static String menu(long first, String parent, String name) {
        String header="insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)";
        String[] actions={"list","query","add","edit","remove","export"};
        StringBuilder sql=new StringBuilder("-- 菜单 SQL\n");
        for(int i=0;i<6;i++) {
            if(i==1)sql.append("-- 按钮 SQL\n");
            sql.append(header).append("\nvalues(").append(first+i).append(", '").append(name).append(i==0?"":"按钮"+i).append("', ");
            sql.append(i==0?"'"+parent+"'":Long.toString(first));
            sql.append(", '").append(i==0?1:i).append("', ").append(i==0?"'demo200', 'module/demo200/index'":"'#', ''");
            sql.append(", 1, 0, '").append(i==0?'C':'F').append("', '0', '0', 'module200:demo:").append(actions[i]);
            sql.append("', '#', 103, 1, sysdate(), null, null, '").append(i==0?name+"菜单":"").append("');\n\n");
        }
        return sql.toString();
    }
    static String stable(String path,String candidate,String baseline) {
        return new String(RuoYiGeneratedMetadata.stabilize(path,candidate.getBytes(StandardCharsets.UTF_8),baseline==null?null:baseline.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8);
    }
    public static void main(String[] args)throws Exception {
        Path root=root();GeneratedSourceWorkspace w=new GeneratedSourceWorkspace(root);Path target=root.resolve(TARGET);
        Map<String,Object> p=plan(w,"v1");check(status(p).equals("create"),"create preview");
        Map<String,Object> first=w.apply(OWNER,(String)p.get("planId"));check(Files.readString(target).equals("v1"),"first apply");
        check(w.apply(OWNER,(String)p.get("planId")).equals(first),"idempotent apply");
        rejects(()->w.apply("other",(String)first.get("planId")),"owner bound");
        check(((List<?>)w.history("other").get("operations")).isEmpty(),"owner filtered history");
        p=plan(w,"v2");check(status(p).equals("update"),"baseline update");Map<String,Object> second=w.apply(OWNER,(String)p.get("planId"));
        check(Files.readString(target).equals("v2"),"updated");w.rollback(OWNER,id(second));check(Files.readString(target).equals("v1"),"rollback restores before");
        check(w.rollback(OWNER,id(second)).get("status").equals("rolled_back"),"rollback idempotent");
        Files.writeString(target,"manual");check(status(plan(w,"v1")).equals("preserve_manual"),"manual preservation");
        check(apply(w,"v1").get("status").equals("noop"),"manual not rewritten");check(Files.readString(target).equals("manual"),"manual intact");
        p=plan(w,"v3");check(!(Boolean)p.get("applicable"),"manual candidate conflict");final String conflict=(String)p.get("planId");rejects(()->w.apply(OWNER,conflict),"conflict atomic rejection");
        rejects(()->w.rollback(OWNER,id(first)),"rollback manual protected");Files.writeString(target,"v1");
        p=plan(w,"v4");final String stale=(String)p.get("planId");Files.writeString(target,"late");rejects(()->w.apply(OWNER,stale),"preview apply race");Files.writeString(target,"v1");
        GeneratedSourceWorkspace reopened=new GeneratedSourceWorkspace(root);check(status(plan(reopened,"v5")).equals("update"),"persistent baseline");check(!((List<?>)reopened.history(OWNER).get("operations")).isEmpty(),"persistent history");
        check(reopened.apply(OWNER,(String)first.get("planId")).equals(first),"persistent idempotency");
        Map<String,Object> multi=reopened.plan(OWNER,Map.of("1",zip(SOURCE,"v6","vue/api/demo.ts","api","vue/views/demo/index.vue","view","main/resources/mapper/demo.xml","mapper"),"2",zip("demoMenu.sql","sql")));
        Map<String,Object> multiApplied=reopened.apply(OWNER,(String)multi.get("planId"));check(((List<?>)multiApplied.get("files")).size()==5,"all native mappings");check(Files.readString(root.resolve("generated/sql/demoMenu.sql")).equals("sql"),"sql write only");reopened.rollback(OWNER,id(multiApplied));check(!Files.exists(root.resolve("generated/sql/demoMenu.sql")),"rollback new file removed");
        Path unowned=root.resolve("integration/plus-ui/src/api/hand.ts");Files.createDirectories(unowned.getParent());Files.writeString(unowned,"hand");
        p=w.plan(OWNER,List.of("1"),zip(SOURCE,"new","vue/api/hand.ts","generated"));final String batch=(String)p.get("planId");rejects(()->w.apply(OWNER,batch),"whole batch conflict");check(Files.readString(target).equals("v1"),"no partial conflict apply");
        rejects(()->w.plan(OWNER,List.of("1"),zip("../escape","x")),"traversal");rejects(()->w.plan(OWNER,List.of("1"),zip("main/java/../escape","x")),"nested traversal");
        rejects(()->w.plan(OWNER,List.of("1"),zip("/main/java/X.java","x")),"absolute path");rejects(()->w.plan(OWNER,List.of("1"),zip("vue\\api\\x","x")),"backslash");rejects(()->w.plan(OWNER,List.of("1"),zip("README.md","x")),"unknown entry");rejects(()->w.plan(OWNER,List.of("1"),new byte[30]),"bad ZIP");
        byte[] symlink=zip(SOURCE,"dest");ByteBuffer buf=ByteBuffer.wrap(symlink).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<symlink.length-46;i++)if(buf.getInt(i)==0x02014b50){buf.putInt(i+38,0120777<<16);break;}
        rejects(()->w.plan(OWNER,List.of("1"),symlink),"ZIP symlink");
        byte[] duplicate=zip("main/java/a","1","main/java/b","2");byte[] from="main/java/b".getBytes(StandardCharsets.UTF_8);for(int i=0;i<=duplicate.length-from.length;i++){boolean same=true;for(int j=0;j<from.length;j++)if(duplicate[i+j]!=from[j])same=false;if(same)duplicate[i+from.length-1]='a';}rejects(()->w.plan(OWNER,List.of("1"),duplicate),"duplicate entry");
        rejects(()->w.plan(OWNER,List.of("1"),zip(SOURCE,"x".repeat(2*1024*1024+1))),"expansion bomb");
        Path linkRoot=root(),outside=root();Files.createSymbolicLink(linkRoot.resolve("integration"),outside);GeneratedSourceWorkspace linked=new GeneratedSourceWorkspace(linkRoot);rejects(()->plan(linked,"x"),"target symlink");
        Path stateRoot=root();Files.createSymbolicLink(stateRoot.resolve(".fcp-generator"),outside);GeneratedSourceWorkspace linkedState=new GeneratedSourceWorkspace(stateRoot);rejects(()->plan(linkedState,"x"),"state symlink");
        // Simulate interruption after one target replacement and before baseline replacement.
        Path recoveryRoot=root();GeneratedSourceWorkspace recovery=new GeneratedSourceWorkspace(recoveryRoot);Map<String,Object> operation=apply(recovery,"crash");String operationId=id(operation);Path manifest=recoveryRoot.resolve(".fcp-generator/operations/"+operationId+"/manifest.properties");Properties props=new Properties();try(InputStream in=Files.newInputStream(manifest)){props.load(in);}props.setProperty("status","pending");try(OutputStream out=Files.newOutputStream(manifest)){props.store(out,"");}
        Files.delete(recoveryRoot.resolve(".fcp-generator/baseline/"+TARGET));Files.writeString(recoveryRoot.resolve(".fcp-generator/active"),operationId);
        GeneratedSourceWorkspace crashed=new GeneratedSourceWorkspace(recoveryRoot);rejects(()->plan(crashed,"anything"),"pending journal blocks planning");rejects(()->crashed.apply(OWNER,(String)operation.get("planId")),"pending journal blocks apply");check((Boolean)crashed.history(OWNER).get("recoveryRequired"),"recovery visible");crashed.rollback(OWNER,operationId);check(!Files.exists(recoveryRoot.resolve(TARGET)),"partial crash rollback");check(!(Boolean)crashed.history(OWNER).get("recoveryRequired"),"recovery clears journal");
        Path concurrentRoot=root();GeneratedSourceWorkspace a=new GeneratedSourceWorkspace(concurrentRoot),b=new GeneratedSourceWorkspace(concurrentRoot);String pa=(String)plan(a,"a").get("planId"),pb=(String)plan(b,"b").get("planId");ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
        futures.add(pool.submit(()->{start.await();try{a.apply(OWNER,pa);return true;}catch(IOException expected){return false;}}));futures.add(pool.submit(()->{start.await();try{b.apply(OWNER,pb);return true;}catch(IOException expected){return false;}}));start.countDown();int success=0;for(Future<Boolean> f:futures)if(f.get())success++;pool.shutdown();check(success==1,"concurrent stale plan rejected");
        rejects(()->w.plan(OWNER,List.of("1"),zip("main/java/a","x","main/java/a/B.java","y")),"file directory collision");
        Path blockedRoot=root();Files.writeString(blockedRoot.resolve("integration"),"not a directory");GeneratedSourceWorkspace blocked=new GeneratedSourceWorkspace(blockedRoot);rejects(()->plan(blocked,"x"),"non-directory ancestor");
        GeneratedSourceWorkspace bounded=new GeneratedSourceWorkspace(root());String evicted=(String)plan(bounded,"old").get("planId");for(int i=0;i<32;i++)plan(bounded,"new"+i);rejects(()->bounded.apply(OWNER,evicted),"plan capacity eviction");
        rejects(()->w.rollback("other",id(first)),"rollback owner bound");
        check(status(plan(w,"v1")).equals("unchanged"),"unchanged preview");
        // Per-table provenance remains stable across split and combined batches.
        Path ownedRoot=root();GeneratedSourceWorkspace owned=new GeneratedSourceWorkspace(ownedRoot);
        Map<String,Object> ownedFirst=apply(owned,"owned-v1");
        Map<String,Object> wrongTable=owned.plan(OWNER,Map.of("2",zip(SOURCE,"owned-v2")));
        check(!(Boolean)wrongTable.get("applicable"),"cross-table overwrite blocked");
        check(!((String)((Map<?,?>)((List<?>)wrongTable.get("files")).get(0)).get("reason")).isEmpty(),"source conflict explained");
        rejects(()->owned.apply(OWNER,(String)wrongTable.get("planId")),"cross-table apply blocked");
        check(!(Boolean)owned.plan("different-owner",Map.of("1",zip(SOURCE,"owned-v1"))).get("applicable"),"cross-owner identical target blocked");
        check(!(Boolean)owned.plan(OWNER,List.of("1","2"),zip(SOURCE,"owned-v2")).get("applicable"),"compatibility group change blocked");
        rejects(()->owned.plan(OWNER,Map.of("1",zip(SOURCE,"same"),"2",zip(SOURCE,"same"))),"same batch source collision");
        Map<String,Object> combined=owned.plan(OWNER,Map.of("1",zip(SOURCE,"owned-v2"),"2",zip("vue/api/other.ts","other-v1")));
        check((Boolean)combined.get("applicable"),"single to combined source stable");Map<String,Object> combinedOp=owned.apply(OWNER,(String)combined.get("planId"));
        check((Boolean)owned.plan(OWNER,Map.of("2",zip("vue/api/other.ts","other-v2"))).get("applicable"),"combined to single source stable");
        owned.rollback(OWNER,id(combinedOp));check((Boolean)plan(owned,"owned-v2").get("applicable"),"rollback restores ownership");
        Path ownership=ownedRoot.resolve(".fcp-generator/ownership/"+TARGET);byte[] origin=Files.readAllBytes(ownership);Files.delete(ownership);
        check(!(Boolean)plan(owned,"owned-v2").get("applicable"),"unattributed legacy baseline blocked");Files.write(ownership,origin);
        String ownerStale=(String)plan(owned,"owned-v2").get("planId");Files.writeString(ownership,"changed");rejects(()->owned.apply(OWNER,ownerStale),"ownership snapshot stale");Files.write(ownership,origin);
        if(Files.getFileAttributeView(ownedRoot,java.nio.file.attribute.PosixFileAttributeView.class)!=null) {
            check(Files.getPosixFilePermissions(ownedRoot.resolve(".fcp-generator")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")),"private state directory permissions");
            check(Files.getPosixFilePermissions(ownedRoot.resolve(".fcp-generator/operations/"+id(ownedFirst)+"/0.after")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),"private backup file permissions");
        }
        Path cappedRoot=root();GeneratedSourceWorkspace capped=new GeneratedSourceWorkspace(cappedRoot);Map<String,Object> cappedFirst=apply(capped,"cap");
        for(int i=0;i<255;i++)Files.createDirectory(cappedRoot.resolve(".fcp-generator/operations/unused-"+i));
        String capPlan=(String)plan(capped,"cap2").get("planId");rejects(()->capped.apply(OWNER,capPlan),"operation count limit");
        check(((List<?>)capped.history(OWNER).get("operations")).size()==1,"history available at operation cap");capped.rollback(OWNER,id(cappedFirst));check(!Files.exists(cappedRoot.resolve(TARGET)),"rollback available at operation cap");
        Path storageRoot=root();GeneratedSourceWorkspace storage=new GeneratedSourceWorkspace(storageRoot);Map<String,Object> storageFirst=apply(storage,"storage");
        try(var file=java.nio.channels.FileChannel.open(storageRoot.resolve(".fcp-generator/quota-fixture"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)){file.position(256L*1024*1024-1);file.write(ByteBuffer.wrap(new byte[]{0}));}
        String storagePlan=(String)plan(storage,"storage2").get("planId");rejects(()->storage.apply(OWNER,storagePlan),"state bytes limit");
        check(((List<?>)storage.history(OWNER).get("operations")).size()==1,"history available at storage cap");storage.rollback(OWNER,id(storageFirst));check(!Files.exists(storageRoot.resolve(TARGET)),"rollback available at storage cap");
        // A manifest persisted before the active marker is also a recovery blocker.
        Path orphanRoot=root();GeneratedSourceWorkspace orphan=new GeneratedSourceWorkspace(orphanRoot);Map<String,Object> orphanOp=apply(orphan,"orphan");
        Path orphanManifest=orphanRoot.resolve(".fcp-generator/operations/"+id(orphanOp)+"/manifest.properties");Properties orphanProps=new Properties();try(InputStream in=Files.newInputStream(orphanManifest)){orphanProps.load(in);}orphanProps.setProperty("status","pending");try(OutputStream out=Files.newOutputStream(orphanManifest)){orphanProps.store(out,"");}
        rejects(()->plan(orphan,"next"),"orphan pending blocks planning");check((Boolean)orphan.history(OWNER).get("recoveryRequired"),"orphan pending visible");orphan.rollback(OWNER,id(orphanOp));check(!Files.exists(orphanRoot.resolve(TARGET)),"orphan pending recoverable");
        String javaOld="/**\n * @date 2026-11-08\n */\nclass Thing { String businessDate = \"2026-11-08\"; }\n";
        String javaNext=javaOld.replace("@date 2026-11-08","@date 2026-11-09");
        check(stable(TARGET,javaNext,javaOld).equals(javaOld),"template generation date stable across days");
        check(stable(TARGET,javaNext,null).equals(javaNext),"first generation date retained");
        check(stable(TARGET,javaNext+" * @date 2026-11-10\n",javaOld).equals(javaNext+" * @date 2026-11-10\n"),"ambiguous date untouched");
        check(stable(TARGET,javaNext.replace("2026-11-09","2026-99-99"),javaOld).contains("2026-99-99"),"invalid date untouched");
        Path dateRoot=root();GeneratedSourceWorkspace dates=new GeneratedSourceWorkspace(dateRoot);apply(dates,javaOld);
        check(status(plan(dates,javaNext)).equals("unchanged"),"cross-day regeneration unchanged");
        Files.writeString(dateRoot.resolve(TARGET),javaOld+"// user customization\n");
        check(status(plan(dates,javaNext)).equals("preserve_manual"),"cross-day manual Java preserved");
        apply(dates,javaNext);check(Files.readString(dateRoot.resolve(TARGET)).endsWith("// user customization\n"),"cross-day apply keeps manual bytes");
        check(status(plan(dates,javaNext.replace("businessDate", "businessDateChanged"))).equals("conflict"),"cross-day business code conflict retained");
        check(status(plan(dates,javaNext.replace("\"2026-11-08\"", "\"2026-11-10\""))).equals("conflict"),"business date change not suppressed");
        String sqlPath="generated/sql/demoMenu.sql",sqlBase=menu(100,"1","菜单200"),sqlNext=menu(200,"1","菜单200");
        check(stable(sqlPath,sqlNext,sqlBase).equals(sqlBase),"six generated IDs and internal parents stable");
        check(stable(sqlPath,sqlNext,null).equals(sqlNext),"first generated IDs retained");
        check(stable(sqlPath,sqlNext,sqlBase).contains("'菜单200'") && stable(sqlPath,sqlNext,sqlBase).contains("'module200:demo:list'"),"numeric text and permission names not replaced");
        check(stable(sqlPath,menu(200,"9","菜单200"),sqlBase).equals(menu(100,"9","菜单200")),"external parent changes retained");
        String malformed=menu(200,"1","有'引号");check(stable(sqlPath,malformed,sqlBase).equals(malformed),"unrecognized quoted SQL name untouched");
        String fiveRows=sqlNext.substring(0,sqlNext.lastIndexOf("insert into"));check(stable(sqlPath,fiveRows,sqlBase).equals(fiveRows),"non-six-row SQL untouched");
        String differentHeader=sqlNext.replace("create_dept", "department");check(stable(sqlPath,differentHeader,sqlBase).equals(differentHeader),"changed SQL template untouched");
        String duplicateIds=sqlNext.replace("values(201,", "values(200,");check(stable(sqlPath,duplicateIds,sqlBase).equals(duplicateIds),"duplicate SQL IDs untouched");
        check(stable("generated/sql/other.sql",sqlNext,sqlBase).equals(sqlNext),"non-menu SQL untouched");
        Path sqlRoot=root();GeneratedSourceWorkspace sqlWorkspace=new GeneratedSourceWorkspace(sqlRoot);
        Map<String,Object> sqlInitial=sqlWorkspace.plan(OWNER,Map.of("1",zip("demoMenu.sql",sqlBase)));sqlWorkspace.apply(OWNER,(String)sqlInitial.get("planId"));
        check(status(sqlWorkspace.plan(OWNER,Map.of("1",zip("demoMenu.sql",sqlNext)))).equals("unchanged"),"new random IDs produce unchanged plan");
        Files.writeString(sqlRoot.resolve(sqlPath),sqlBase+"-- manual permission adjustment\n");
        Map<String,Object> sqlPreserved=sqlWorkspace.plan(OWNER,Map.of("1",zip("demoMenu.sql",sqlNext)));check(status(sqlPreserved).equals("preserve_manual"),"random IDs do not conflict with manual SQL");sqlWorkspace.apply(OWNER,(String)sqlPreserved.get("planId"));
        check(Files.readString(sqlRoot.resolve(sqlPath)).endsWith("-- manual permission adjustment\n"),"SQL manual adjustment retained");
        check(status(sqlWorkspace.plan(OWNER,Map.of("1",zip("demoMenu.sql",menu(200,"1","业务名称变更"))))).equals("conflict"),"SQL business name change still conflicts");
        check(status(sqlWorkspace.plan(OWNER,Map.of("1",zip("demoMenu.sql",menu(200,"9","菜单200"))))).equals("conflict"),"SQL external parent change still conflicts");
        // ISO Instant strings have variable fractional precision; sort actual instants.
        Path orderingRoot=root();GeneratedSourceWorkspace ordering=new GeneratedSourceWorkspace(orderingRoot);
        Map<String,Object> oldOrder=apply(ordering,"v1"),newOrder=apply(ordering,"v2");
        for(var value:List.of(oldOrder,newOrder)) {
            Path m=orderingRoot.resolve(".fcp-generator/operations/"+id(value)+"/manifest.properties");Properties orderProps=new Properties();
            try(InputStream in=Files.newInputStream(m)){orderProps.load(in);}
            orderProps.setProperty("createdAt",id(value).equals(id(oldOrder))?"2026-01-01T00:00:00Z":"2026-01-01T00:00:00.001Z");
            try(OutputStream out=Files.newOutputStream(m)){orderProps.store(out,"");}
        }
        check(((Map<?,?>)((List<?>)ordering.history(OWNER).get("operations")).get(0)).get("operationId").equals(id(newOrder)),"history uses chronological instant ordering");
        System.out.println("RESULT "+assertions+" generator-workspace checks PASS");
    }
}
